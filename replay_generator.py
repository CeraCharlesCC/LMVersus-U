#!/usr/bin/env python3
"""
Replay Generator for LMVersus-U

Loads a Premium spec, feeds questions from the associated question set
to the LLM API sequentially, and saves the results as replay files.

Usage:
    python replay_generator.py [--auto] [--output-dir <path>] [--limit <n>] [--resume <n>]

Options:
    --auto          Process all questions without waiting for Enter key
    --output-dir    Custom output directory (default: ./replay_output/{spec_id}/)
    --limit         Process only the first N questions
    --resume        Resume from question index N (1-based). Use 0 to auto-resume from
                    the first missing replay file in the output directory.
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

try:
    import openai
except ImportError:
    print("Error: openai package not installed. Run: pip install openai")
    sys.exit(1)


# ─────────────────────────────────────────────────────────────────────────────
# Configuration
# ─────────────────────────────────────────────────────────────────────────────

LLM_CONFIGS_DIR = Path(__file__).parent / "LLM-Configs"
ENV_PREFIX = "ENV:"


# ─────────────────────────────────────────────────────────────────────────────
# Spec Loading
# ─────────────────────────────────────────────────────────────────────────────

def resolve_env_secret(value: str) -> str:
    """Resolve ENV:VAR_NAME patterns to environment variable values."""
    trimmed = value.strip()
    if not trimmed.startswith(ENV_PREFIX):
        return trimmed
    env_name = trimmed[len(ENV_PREFIX):].strip()
    if not env_name:
        return ""
    return os.environ.get(env_name, "").strip()


def load_premium_specs() -> list[dict[str, Any]]:
    """Load all Premium specs from LLM-Configs directory."""
    specs: list[dict[str, Any]] = []
    if not LLM_CONFIGS_DIR.is_dir():
        print(f"Error: LLM-Configs directory not found at {LLM_CONFIGS_DIR}")
        return specs

    for json_file in LLM_CONFIGS_DIR.glob("*.json"):
        try:
            with open(json_file, "r", encoding="utf-8") as f:
                data = json.load(f)

            if isinstance(data, dict) and data.get("mode") == "PREMIUM":
                # Resolve environment secrets
                if "provider" in data:
                    provider = data["provider"]
                    provider["providerName"] = resolve_env_secret(provider.get("providerName", ""))
                    provider["apiUrl"] = resolve_env_secret(provider.get("apiUrl", ""))
                    provider["apiKey"] = resolve_env_secret(provider.get("apiKey", ""))
                specs.append(data)
        except (json.JSONDecodeError, OSError) as e:
            print(f"Warning: Failed to load {json_file}: {e}")
    return specs


# ─────────────────────────────────────────────────────────────────────────────
# Question Loading
# ─────────────────────────────────────────────────────────────────────────────

def load_questions(question_set_path: str) -> list[dict[str, Any]]:
    """Load questions from a question set directory."""
    # Resolve relative paths from repo root
    base_dir = Path(__file__).parent
    question_dir = base_dir / question_set_path

    manifest_path = question_dir / "manifest.json"
    if not manifest_path.is_file():
        print(f"Error: Manifest not found at {manifest_path}")
        return []

    try:
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        if not isinstance(manifest, dict):
            print(f"Error: Manifest at {manifest_path} is not a dictionary.")
            return []
    except (json.JSONDecodeError, OSError) as e:
        print(f"Error: Failed to load manifest: {e}")
        return []

    questions: list[dict[str, Any]] = []
    questions_dir = question_dir / "questions"

    for question_id in manifest.get("questionIds", []):
        question_file = questions_dir / f"{question_id}.json"
        if not question_file.is_file():
            print(f"Warning: Question file not found: {question_file}")
            continue

        try:
            with open(question_file, "r", encoding="utf-8") as f:
                question_data = json.load(f)
            if isinstance(question_data, dict):
                questions.append(question_data)
            else:
                print(f"Warning: Question data in {question_file} is not a dictionary.")
        except (json.JSONDecodeError, OSError) as e:
            print(f"Warning: Failed to load question {question_id}: {e}")

    return questions


# ─────────────────────────────────────────────────────────────────────────────
# Resume Helpers
# ─────────────────────────────────────────────────────────────────────────────

def collect_existing_replay_ids(output_dir: Path) -> set[str]:
    """
    Collect question IDs that already have replay files in output_dir/replays/.

    We use the filename stem as the questionId because save_replay() writes
    {questionId}.json.
    """
    replays_dir = output_dir / "replays"
    if not replays_dir.is_dir():
        return set()
    return {p.stem for p in replays_dir.glob("*.json") if p.is_file()}


def compute_resume_index(
        resume_arg: int,
        questions: list[dict[str, Any]],
        existing_ids: set[str]
) -> int:
    """
    Return a 0-based start index into `questions`.

    - If resume_arg > 0: treat as 1-based question index, so start = resume_arg - 1
    - If resume_arg == 0: auto-resume from first missing replay (by question order)
    """
    if resume_arg < 0:
        raise ValueError("--resume must be >= 0")

    if resume_arg == 0:
        for idx, q in enumerate(questions):
            qid = q.get("questionId", "unknown")
            if qid not in existing_ids:
                return idx
        return len(questions)  # everything already done

    # resume_arg is 1-based
    start = resume_arg - 1
    if start < 0:
        start = 0
    if start > len(questions):
        start = len(questions)
    return start


# ─────────────────────────────────────────────────────────────────────────────
# Prompt Building (matching OpenAIApiDao.buildPromptParts)
# ─────────────────────────────────────────────────────────────────────────────

def determine_expected_kind(question: dict[str, Any]) -> str:
    """Determine the expected answer kind based on question structure."""
    if question.get("choices"):
        return "multiple_choice"
    verifier_spec = question.get("verifierSpec", {})
    verifier_type = verifier_spec.get("type", "")
    if verifier_type == "integer_range":
        return "integer"
    return "free_text"


def build_prompts(question: dict[str, Any]) -> tuple[str, str]:
    """Build system and user prompts for the LLM."""
    prompt = question.get("prompt", "").strip()
    choices = question.get("choices")
    expected_kind = determine_expected_kind(question)

    # Build user prompt
    user_parts = [prompt]
    if choices:
        user_parts.append("\n\nChoices (0-based index):")
        for i, choice in enumerate(choices):
            user_parts.append(f"{i}) {choice}")
    user_prompt = "\n".join(user_parts)

    # Build system prompt
    system_parts = [
        "You are the opponent player in a quiz game.",
        "Return ONLY valid JSON (no markdown, no code fences, no extra text).",
        "Output in this format:",
        ""
    ]

    if expected_kind == "multiple_choice":
        system_parts.append('{"finalAnswer":{"type":"multiple_choice","choiceIndex":0}}')
        system_parts.append("")
        system_parts.append("Rules:")
        system_parts.append("- choiceIndex MUST be a 0-based index into the provided choices.")
    elif expected_kind == "integer":
        system_parts.append('{"finalAnswer":{"type":"integer","value":0}}')
        system_parts.append("")
        system_parts.append("Rules:")
        system_parts.append("- value must be an integer.")
    else:  # free_text
        system_parts.append('{"finalAnswer":{"type":"free_text","text":"..."}}')
        system_parts.append("")
        system_parts.append("Rules:")
        system_parts.append("- text must be a plain string answer.")

    system_prompt = "\n".join(system_parts)

    return system_prompt, user_prompt


# ─────────────────────────────────────────────────────────────────────────────
# LLM API Call
# ─────────────────────────────────────────────────────────────────────────────

def extract_json_object(text: str) -> dict[str, Any] | None:
    """Extract a JSON object from text, handling markdown code fences."""
    # Try direct JSON parse first
    try:
        return json.loads(text.strip())
    except json.JSONDecodeError:
        pass

    # Try to find JSON object in the text
    start = text.find("{")
    while start != -1:
        depth = 0
        in_string = False
        escaped = False
        for i in range(start, len(text)):
            ch = text[i]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
            else:
                if ch == '"':
                    in_string = True
                elif ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        candidate = text[start:i + 1]
                        try:
                            return json.loads(candidate)
                        except json.JSONDecodeError:
                            break
        start = text.find("{", start + 1)

    return None


def call_llm_api(
        spec: dict[str, Any],
        question: dict[str, Any]
) -> tuple[str | None, dict[str, Any] | None, dict[str, Any]]:
    """
    Call the LLM API and return (reasoning, final_answer, usage_info).

    Returns:
        - reasoning: The reasoning text (if available)
        - final_answer: The parsed finalAnswer dict
        - usage_info: Token usage information
    """
    provider = spec.get("provider", {})
    llm_profile = spec.get("llmProfile", {})

    api_key = provider.get("apiKey", "")
    api_url = provider.get("apiUrl", "")
    model = llm_profile.get("modelName", "")
    temperature = llm_profile.get("temperature", 0.6)
    max_tokens = llm_profile.get("maxTokens", 4096)

    if not api_key:
        print("Error: API key not configured")
        return None, None, {}

    # Build prompts
    system_prompt, user_prompt = build_prompts(question)

    # Configure OpenAI client
    client = openai.OpenAI(
        api_key=api_key,
        base_url=api_url if api_url else None
    )

    # Determine response format based on compat settings
    compat = provider.get("compat", {})
    structured_output = compat.get("structuredOutput", "JSON_OBJECT")

    extra_body = provider.get("extraBody", {})

    start_time = time.time()

    try:
        # Make API call
        kwargs: dict[str, Any] = {
            "model": model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            "temperature": temperature,
            "max_completion_tokens": max_tokens
        }

        # Add response format if structured output is configured
        if structured_output == "JSON_OBJECT":
            kwargs["response_format"] = {"type": "json_object"}

        # Add extra body parameters
        if extra_body:
            kwargs["extra_body"] = extra_body

        response = client.chat.completions.create(**kwargs)
        elapsed_time = time.time() - start_time

    except Exception as e:
        print(f"Error calling LLM API: {e}")
        return None, None, {}

    # Extract response content
    message = response.choices[0].message
    content = message.content or ""

    # Try to extract reasoning from different fields
    reasoning = None
    if hasattr(message, "reasoning_content"):
        reasoning = message.reasoning_content
    elif hasattr(message, "reasoning"):
        reasoning = message.reasoning

    # Parse the JSON response
    parsed = extract_json_object(content)
    final_answer = parsed.get("finalAnswer") if parsed else None

    # Calculate usage info
    usage = response.usage
    usage_info = {
        "prompt_tokens": usage.prompt_tokens if usage else 0,
        "completion_tokens": usage.completion_tokens if usage else 0,
        "total_tokens": usage.total_tokens if usage else 0,
        "elapsed_seconds": elapsed_time
    }

    # If no reasoning from special fields, use content before JSON
    if not reasoning and content:
        json_start = content.find("{")
        if json_start > 0:
            reasoning = content[:json_start].strip()

    return reasoning, final_answer, usage_info


# ─────────────────────────────────────────────────────────────────────────────
# Replay Saving
# ─────────────────────────────────────────────────────────────────────────────

def build_embedded_verifier_hint(question: dict[str, Any]) -> dict[str, Any]:
    """Build the embeddedVerifierHint from the question's verifierSpec."""
    verifier_spec = question.get("verifierSpec", {})
    verifier_type = verifier_spec.get("type", "")

    if verifier_type == "multiple_choice":
        return {
            "type": "multiple_choice",
            "correctIndex": verifier_spec.get("correctIndex", 0)
        }
    elif verifier_type == "integer_range":
        return {
            "type": "integer",
            "correctValue": verifier_spec.get("correctValue", 0)
        }
    else:
        # free_response or unknown
        return {
            "type": "free_response",
            "rubric": verifier_spec.get("rubric"),
            "expectedKeywords": verifier_spec.get("expectedKeywords", [])
        }


def save_replay(
        output_dir: Path,
        question: dict[str, Any],
        reasoning: str | None,
        final_answer: dict[str, Any] | None,
        usage_info: dict[str, Any]
) -> None:
    """Save replay data to a JSON file."""
    question_id = question.get("questionId", "unknown")

    # Estimate token count from reasoning
    reasoning_text = reasoning or ""
    reasoning_token_count = len(reasoning_text) // 4  # rough estimate

    # Calculate tokens per second
    elapsed = usage_info.get("elapsed_seconds", 1)
    completion_tokens = usage_info.get("completion_tokens", 0)
    avg_tps = int(completion_tokens / elapsed) if elapsed > 0 else 40

    replay_data = {
        "questionId": question_id,
        "llmReasoning": reasoning_text,
        "llmFinalAnswer": final_answer or {"type": "free_text", "text": ""},
        "embeddedVerifierHint": build_embedded_verifier_hint(question),
        "replay": {
            "reasoningTokenCount": reasoning_token_count,
            "avgTokensPerSecond": avg_tps
        }
    }

    # Ensure output directory exists
    replays_dir = output_dir / "replays"
    replays_dir.mkdir(parents=True, exist_ok=True)

    output_file = replays_dir / f"{question_id}.json"
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(replay_data, f, indent=2, ensure_ascii=False)

    print(f"  ✓ Saved replay: {output_file}")


def save_manifest(
        output_dir: Path,
        spec: dict[str, Any],
        question_ids: list[str]
) -> None:
    """Save a manifest file for the generated replays."""
    manifest_data = {
        "packId": spec.get("id", "unknown"),
        "version": 1,
        "questionSetPath": spec.get("questionSetPath", ""),
        "availableQuestionIds": question_ids,
        "llmProfile": spec.get("llmProfile", {})
    }

    manifest_file = output_dir / "manifest.json"
    with open(manifest_file, "w", encoding="utf-8") as f:
        json.dump(manifest_data, f, indent=2, ensure_ascii=False)

    print(f"  ✓ Saved manifest: {manifest_file}")


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def select_spec(specs: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Interactively select a Premium spec."""
    print("\n" + "=" * 60)
    print("Available Premium Specs:")
    print("=" * 60)

    for i, spec in enumerate(specs, 1):
        spec_id = spec.get("id", "unknown")
        metadata = spec.get("metadata", {})
        display_name = metadata.get("displayName", spec_id)
        model_name = spec.get("llmProfile", {}).get("modelName", "unknown")
        question_set = spec.get("questionSetPath", "")
        print(f"  {i}. {display_name}")
        print(f"     Model: {model_name}")
        print(f"     Question Set: {question_set}")
        print()

    while True:
        try:
            choice = input("Select spec number (or 'q' to quit): ").strip()
            if choice.lower() == "q":
                return None
            idx = int(choice) - 1
            if 0 <= idx < len(specs):
                return specs[idx]
            print(f"Invalid choice. Enter 1-{len(specs)}")
        except ValueError:
            print("Please enter a valid number")


def main():
    parser = argparse.ArgumentParser(
        description="Generate LLM replays from a Premium spec's question set"
    )
    parser.add_argument(
        "--auto",
        action="store_true",
        help="Process all questions without waiting for Enter key"
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        help="Custom output directory"
    )
    parser.add_argument(
        "--limit",
        type=int,
        help="Process only the first N questions"
    )
    parser.add_argument(
        "--resume",
        type=int,
        default=1,
        help="Resume from question index N (1-based). Use 0 to auto-resume from first missing replay."
    )

    args = parser.parse_args()

    # Load Premium specs
    print("\nLoading Premium specs...")
    specs = load_premium_specs()

    if not specs:
        print("No Premium specs found in LLM-Configs/")
        sys.exit(1)

    print(f"Found {len(specs)} Premium spec(s)")

    # Select a spec
    spec = select_spec(specs)
    if not spec:
        print("Cancelled.")
        sys.exit(0)

    print(f"\nSelected: {spec.get('metadata', {}).get('displayName', spec.get('id'))}")

    # Determine output directory
    spec_id = spec.get("id", "unknown")
    if args.output_dir:
        output_dir = Path(args.output_dir)
    else:
        output_dir = Path(__file__).parent / "replay_output" / spec_id

    print(f"Output directory: {output_dir}")

    # Load questions
    question_set_path = spec.get("questionSetPath", "")
    print(f"\nLoading questions from: {question_set_path}")
    questions = load_questions(question_set_path)

    if not questions:
        print("No questions found.")
        sys.exit(1)

    # Apply limit (so resume index lines up with what you'll actually run)
    if args.limit and args.limit > 0:
        questions = questions[:args.limit]

    print(f"Loaded {len(questions)} question(s)")

    # Resume support: detect existing replay files, compute start index
    existing_ids = collect_existing_replay_ids(output_dir)
    try:
        start_index = compute_resume_index(args.resume, questions, existing_ids)
    except ValueError as e:
        parser.error(str(e))
        return

    if args.resume == 0:
        if start_index >= len(questions):
            print("Auto-resume: all questions already have replay files. Nothing to do.")
        else:
            print(f"Auto-resume: first missing replay is question #{start_index + 1}.")
    elif args.resume > 1:
        print(f"Resuming from question #{start_index + 1}.")

    # Process each question
    print("\n" + "=" * 60)
    print("Processing Questions")
    print("=" * 60)

    # Track processed IDs (existing + new), but write manifest in question order
    processed_set: set[str] = set(existing_ids)

    for idx in range(start_index, len(questions)):
        question = questions[idx]
        question_id = question.get("questionId", "unknown")

        # Skip already-generated replay files (useful for reruns)
        if question_id in processed_set:
            print(f"\n[{idx + 1}/{len(questions)}] Question: {question_id}")
            print("  ↷ Skipping (replay already exists)")
            continue

        prompt_preview = question.get("prompt", "")[:80]
        if len(question.get("prompt", "")) > 80:
            prompt_preview += "..."

        print(f"\n[{idx + 1}/{len(questions)}] Question: {question_id}")
        print(f"  Prompt: {prompt_preview}")

        if not args.auto:
            input("  Press Enter to process this question...")

        print("  Calling LLM API...")
        reasoning, final_answer, usage_info = call_llm_api(spec, question)

        if final_answer:
            print(f"  Answer: {json.dumps(final_answer)}")
            save_replay(output_dir, question, reasoning, final_answer, usage_info)
            processed_set.add(question_id)
        else:
            print("  ✗ Failed to get a valid answer")

    # Save manifest (in the original question order)
    ordered_processed_ids = [
        q.get("questionId", "unknown")
        for q in questions
        if q.get("questionId", "unknown") in processed_set
    ]

    if ordered_processed_ids:
        print("\n" + "-" * 60)
        save_manifest(output_dir, spec, ordered_processed_ids)

    print("\n" + "=" * 60)
    print(f"Done! Processed {len(ordered_processed_ids)}/{len(questions)} questions")
    print("=" * 60)


if __name__ == "__main__":
    main()
