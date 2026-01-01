#!/usr/bin/env python3
import argparse
import datetime as dt
import os
import re
import subprocess
import sys
from typing import List, Optional, Tuple

from openai import OpenAI


def env(name: str, default: Optional[str] = None) -> Optional[str]:
    """Return env var value, treating empty string as unset."""
    v = os.getenv(name)
    if v is None or v.strip() == "":
        return default
    return v


def env_float(name: str, default: float) -> float:
    v = env(name)
    if v is None:
        return default
    try:
        return float(v)
    except ValueError:
        return default


def env_int(name: str, default: int) -> int:
    v = env(name)
    if v is None:
        return default
    try:
        return int(v)
    except ValueError:
        return default


def run(cmd: List[str], cwd: Optional[str] = None, check: bool = True) -> str:
    """Run a shell command and return stdout (stripped)."""
    res = subprocess.run(cmd, cwd=cwd, check=check, capture_output=True, text=True)
    return res.stdout.strip()


def get_all_tags() -> List[str]:
    out = run(["git", "tag", "--list"])
    return [t for t in out.splitlines() if t]


def tag_exists(tag: str) -> bool:
    try:
        run(["git", "rev-parse", "-q", "--verify", f"refs/tags/{tag}"], check=True)
        return True
    except subprocess.CalledProcessError:
        return False


SEMVER_RE = re.compile(r"^(?:v)?(?P<major>\d+)\.(?P<minor>\d+)\.(?P<patch>\d+)(?P<rest>.*)?$")


def parse_semver(tag: str) -> Optional[Tuple[int, int, int, str]]:
    m = SEMVER_RE.match(tag)
    if not m:
        return None
    return (
        int(m.group("major")),
        int(m.group("minor")),
        int(m.group("patch")),
        m.group("rest") or "",
    )


def previous_tag_for(new_tag: str, all_tags: List[str]) -> Optional[str]:
    """Try to find the previous tag by semver; fallback to creation-date order."""
    parsed_new = parse_semver(new_tag)
    if parsed_new:
        candidates: List[Tuple[Tuple[int, int, int, str], str]] = []
        for t in all_tags:
            if t == new_tag:
                continue
            sv = parse_semver(t)
            if sv:
                candidates.append((sv, t))
        lower = [t for (sv, t) in candidates if sv < parsed_new]
        if lower:
            lower.sort()
            return lower[-1]

    out = run(
        [
            "git",
            "for-each-ref",
            "--sort=-creatordate",
            "--format=%(refname:short)",
            "refs/tags",
        ]
    )
    ordered = [t for t in out.splitlines() if t]
    if new_tag in ordered:
        idx = ordered.index(new_tag)
        if idx + 1 < len(ordered):
            return ordered[idx + 1]
    return None


def repo_https_url() -> Optional[str]:
    try:
        remote = run(["git", "config", "--get", "remote.origin.url"])
    except subprocess.CalledProcessError:
        return None
    remote = remote.strip()

    owner_repo = None
    if remote.startswith("git@github.com:"):
        owner_repo = remote.split("git@github.com:")[1]
    elif remote.startswith("https://github.com/"):
        owner_repo = remote.split("https://github.com/")[1]
    elif remote.startswith("http://github.com/"):
        owner_repo = remote.split("http://github.com/")[1]

    if not owner_repo:
        return None
    if owner_repo.endswith(".git"):
        owner_repo = owner_repo[:-4]
    return f"https://github.com/{owner_repo}"


def git_range_or_initial(prev_tag: Optional[str], end_ref: str) -> str:
    if prev_tag:
        return f"{prev_tag}..{end_ref}"
    first = run(["git", "rev-list", "--max-parents=0", "HEAD"]).splitlines()[0]
    return f"{first}..{end_ref}"


def collect_commits(git_range: str, limit_chars: int = 120_000) -> str:
    """
    Collect commit subjects and bodies in a compact, parseable way.
    Uses ASCII record/field separators to avoid accidental delimiter collisions.
    """
    # RS=0x1e, FS=0x1f
    fmt = "%H%x1f%h%x1f%an%x1f%ad%x1f%s%x1f%b%x1e"
    log = run(["git", "log", "--date=short", f"--pretty=format:{fmt}", git_range])

    records = log.split("\x1e")
    out_lines: List[str] = []
    total = 0

    for rec in records:
        rec = rec.strip()
        if not rec:
            continue
        if total + len(rec) > limit_chars:
            out_lines.append("\n[... truncated commit list ...]\n")
            break
        fields = rec.split("\x1f")
        # Keep raw but predictable; the instruct file can teach the model how to interpret.
        out_lines.append("|||".join(f.strip() for f in fields))
        total += len(rec)

    return "\n".join(out_lines)


def collect_diff_summary(git_range: str, limit_chars: int = 80_000) -> str:
    try:
        name_status = run(["git", "diff", "--name-status", git_range])
    except subprocess.CalledProcessError:
        name_status = ""
    try:
        shortstat = run(["git", "diff", "--shortstat", git_range])
    except subprocess.CalledProcessError:
        shortstat = ""

    diff = f"""# File changes (git diff --name-status {git_range})
{name_status}

# Summary (git diff --shortstat {git_range})
{shortstat}
"""
    if len(diff) > limit_chars:
        diff = diff[:limit_chars] + "\n[... diff summary truncated ...]\n"
    return diff.strip() + "\n"


def ensure_changelog(path: str) -> None:
    if os.path.exists(path):
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(
            "# Changelog\n\n"
            "All notable changes to this project will be documented in this file.\n\n"
        )


def prepend_after_top_header(changelog_text: str, new_section_text: str) -> str:
    m = re.match(r"^# .*\n(\n+)?", changelog_text)
    if m:
        insert_at = m.end()
        return changelog_text[:insert_at] + new_section_text + "\n" + changelog_text[insert_at:]
    return new_section_text + "\n" + changelog_text


def insert_or_replace_section(changelog_text: str, new_section_text: str, version_header: str) -> str:
    """
    Insert the new section after the top header OR replace an existing section
    that begins with the given version header (matching by version token only).
    """
    m = re.match(r"^##\s*\[\s*(?P<ver>[^]]+)\s*]", version_header)
    if not m:
        return prepend_after_top_header(changelog_text, new_section_text)

    version_token = re.escape(m.group("ver"))
    section_start_re = re.compile(rf"(?m)^##\s*\[\s*{version_token}\s*].*$")
    matches = list(section_start_re.finditer(changelog_text))
    if not matches:
        return prepend_after_top_header(changelog_text, new_section_text)

    start_idx = matches[0].start()
    next_header = re.compile(r"(?m)^##\s*\[")
    next_match = next_header.search(changelog_text, matches[0].end())
    end_idx = next_match.start() if next_match else len(changelog_text)

    return changelog_text[:start_idx] + new_section_text + "\n" + changelog_text[end_idx:]


def build_user_context(
        repo_url: Optional[str],
        prev_tag: Optional[str],
        new_tag: str,
        commits: str,
        diff_summary: str,
) -> str:
    today = dt.date.today().isoformat()
    compare_url = None
    if repo_url and prev_tag:
        compare_url = f"{repo_url}/compare/{prev_tag}...{new_tag}"
    elif repo_url:
        compare_url = f"{repo_url}/tree/{new_tag}"

    header = [
        f"Repository: {repo_url or 'unknown'}",
        f"New tag: {new_tag}",
        f"Previous tag: {prev_tag or '(none — first release or tag)'}",
        f"Release date (YYYY-MM-DD): {today}",
    ]
    if compare_url:
        header.append(f"Compare URL: {compare_url}")

    body = f"""{os.linesep.join(header)}

=== COMMITS BEGIN ===
{commits}
=== COMMITS END ===

=== DIFF SUMMARY BEGIN ===
{diff_summary}
=== DIFF SUMMARY END ===
"""
    return body.strip()


def normalize_base_url(base_url: str) -> str:
    # OpenAI client expects base_url like https://host/v1
    base_url = base_url.rstrip("/")
    if base_url.endswith("/chat/completions"):
        base_url = base_url[: -len("/chat/completions")]
    if base_url.endswith("/completions"):
        base_url = base_url[: -len("/completions")]
    return base_url


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate and prepend a changelog entry using an LLM.")
    parser.add_argument("--new-tag", dest="new_tag", default=env("NEW_TAG"))
    parser.add_argument("--prev-tag", dest="prev_tag", default=env("PREV_TAG"))
    parser.add_argument("--instruct", dest="instruct_path", default=env("INSTRUCT_PATH", "changelog_instruct.txt"))
    parser.add_argument("--changelog", dest="changelog_path", default=env("CHANGELOG_PATH", "CHANGELOG.md"))
    parser.add_argument("--model", dest="model", default=env("LLM_MODEL", "gpt-4o-mini"))
    parser.add_argument("--base-url", dest="base_url", default=env("LLM_BASE_URL", "https://api.openai.com/v1"))
    parser.add_argument("--api-key", dest="api_key", default=env("LLM_API_KEY"))
    parser.add_argument("--temperature", type=float, default=env_float("LLM_TEMPERATURE", 0.2))
    parser.add_argument("--max-tokens", type=int, default=env_int("LLM_MAX_TOKENS", 8192))
    args = parser.parse_args()

    if not args.new_tag:
        print("ERROR: --new-tag or NEW_TAG env var is required (e.g., v1.2.3).", file=sys.stderr)
        return 2

    if not args.api_key:
        print("ERROR: LLM_API_KEY not provided (set it as a GitHub Actions secret).", file=sys.stderr)
        return 2

    if not os.path.exists(args.instruct_path):
        print(f"ERROR: instructions file not found: {args.instruct_path}", file=sys.stderr)
        return 2

    all_tags = get_all_tags()
    prev_tag = args.prev_tag or previous_tag_for(args.new_tag, all_tags)

    end_ref = args.new_tag if tag_exists(args.new_tag) else "HEAD"
    rng = git_range_or_initial(prev_tag, end_ref)

    commits = collect_commits(rng)
    diff_summary = collect_diff_summary(rng)
    repo_url = repo_https_url()
    user_context = build_user_context(repo_url, prev_tag, args.new_tag, commits, diff_summary)

    with open(args.instruct_path, "r", encoding="utf-8") as f:
        system_instructions = f.read().strip()

    base_url = normalize_base_url(args.base_url or "https://api.openai.com/v1")
    client = OpenAI(base_url=base_url, api_key=args.api_key)

    messages = [
        {"role": "system", "content": system_instructions},
        {"role": "user", "content": user_context},
    ]

    completion = client.chat.completions.create(
        model=args.model,
        messages=messages,
        temperature=args.temperature,
        top_p=0.9,
        max_tokens=args.max_tokens,
    )
    content = (completion.choices[0].message.content or "").strip()
    if not content:
        print("ERROR: LLM returned empty content.", file=sys.stderr)
        return 2

    header_line = f"## [{args.new_tag}] - {dt.date.today().isoformat()}"
    if not content.lstrip().startswith("## ["):
        content = f"{header_line}\n\n{content}"
    else:
        header_line = content.splitlines()[0].strip()

    ensure_changelog(args.changelog_path)
    with open(args.changelog_path, "r", encoding="utf-8") as f:
        old = f.read()

    new_text = insert_or_replace_section(old, content.strip() + "\n", header_line)

    if new_text != old:
        with open(args.changelog_path, "w", encoding="utf-8") as f:
            f.write(new_text)
        print(f"CHANGELOG updated for {args.new_tag}.")
    else:
        print("CHANGELOG already up to date for this tag.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
