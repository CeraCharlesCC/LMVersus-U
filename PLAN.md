# LMVersus-U Plan

## What we’re building

LMVersus-U is a small real-time “race” game: a human and an LLM answer the same questions, and the UI makes it feel like you’re “fighting” the model. A session runs a fixed number of rounds (3 for now). The server is authoritative for timers and scoring, and it streams LLM progress to the client via WebSocket.

There are two operating modes:

* **LIGHTWEIGHT**: offline replay using a precomputed dataset that already contains questions + model reasoning + model final answers.
* **PREMIUM**: live LLM calls to an external provider at runtime (dataset contains questions + ground truth, but the model output is generated live).

A key design requirement is that **the streaming logic must be replaceable** and **must not be coupled to Lightweight**. Lightweight is just one possible backend for `LlmPlayerGateway`.

---

## Guiding constraints

The project must stay simple. The “secret sauce” is only:

1. a clean separation between “where the LLM output comes from” and “how it is streamed to the UI”, and
2. a simple on-disk config layout next to the `.jar`.

Everything else should remain minimal and easy to swap.

---

## Runtime file layout (next to the .jar)

At runtime, the app reads configs relative to the directory containing `config.toml` (your `ConfigLoader` already sets `lmversusu.configDir`).

Recommended layout:

```
<run-dir>/
  config.toml
  LLM-Configs/
    lightweight-modelspec.json
    premium-modelspec.json
    (more specs later...)
  Datasets/
    lightweight/
      sonnet45_mmlu_pro_subset/
        items.jsonl
        manifest.json
    question-sets/
      mmlu_pro_subset.jsonl
```

Notes:

* `LLM-Configs/` contains “what opponents exist” and how to run them.
* `Datasets/` contains question sets and (for Lightweight) replay items including reasoning and final answers.
* Keep secrets out of JSON when possible. If you *must* include API keys in JSON, support `ENV:` like `config.toml` does.

Your `ConfigLoader.copyDefaultLlmConfigs()` already copies `src/main/resources/LLM-Configs/*.json` to `<run-dir>/LLM-Configs/` on first run. This is exactly the right direction; the plan below assumes we keep that behavior.

---

## Core concept: Separate “source of output” from “shape of stream”

You want this UX:

* LLM thinking starts
* reasoning is produced immediately, but **only becomes visible after ~10s**
* once the final answer is ready, **flush remaining buffered reasoning quickly** and then show the final answer immediately

That is not a Lightweight-only concern. It is a **stream shaping** policy.

So we treat the pipeline as two independent layers:

1. **Source**: `LlmPlayerGateway` produces `Flow<LlmStreamEvent>`

    * Lightweight reads a precomputed dataset and emits events
    * Premium connects to a provider and emits events

2. **Shaper**: `LlmStreamOrchestrator` takes that `Flow` and re-times it (delay, target TPS, flush-on-final, caps)

The shaper is where “TPS × 5”, “10s reveal delay”, and “burst flush” live. The source should stay dumb.

---

## WebSocket event model for real-time UI updates

The client should be able to update two visible fields continuously:

* `reasoningSummary` (append deltas)
* `finalAnswer` (set once at the end)

So the server should emit two WS event types during a round:

* `llm_reasoning_delta` (append-only text)
* `llm_final_answer` (the parsed `LlmAnswer`)

Optionally, a cosmetic signal:

* `llm_thinking` (spinner/progress)

You can either add these to `GameEvent` or keep them as WS-only DTOs. Since you already have `GameEventBus` and `GameEvent`, the least invasive approach is to extend `GameEvent` with two new events:

* `GameEvent.LlmReasoningDelta(sessionId, roundId, deltaText, seq)`
* `GameEvent.LlmFinalAnswer(sessionId, roundId, answer)`

Then `presentation` maps `GameEvent` to WS frames.

This keeps the use cases emitting domain-ish events, while the WS layer stays a translator.

---

## Streaming policy (the “fight feel”)

Define a small, explicit policy object that can be configured per opponent:

**Policy behaviors**

* `revealDelayMs`: how long the server buffers reasoning before sending any of it
* `targetTokensPerSecond`: baseline playback rate once revealing starts
* `burstMultiplierOnFinal`: when final arrives, replay remaining buffered reasoning at `targetTPS * multiplier` (or as fast as possible)
* `maxBufferedChars`: safety cap to avoid runaway memory usage

This policy must be applied to the stream regardless of whether the underlying stream is live (Premium) or replayed (Lightweight).

**Important simplification**
“Tokens per second” is an approximation unless your provider gives exact token counts. For Lightweight you can store token counts in the dataset, or approximate by characters. The policy should be phrased in a way that tolerates approximation.

---

## Model specs: keep them simple, mode-specific

You requested that Lightweight and Premium specs be separated because their operational mental model differs. The simplest way to honor that without over-engineering is:

* `lightweight-modelspec.json`: “This model replay package contains its own problem set.”
* `premium-modelspec.json`: “This problem set is solved by this live model/provider.”

### Lightweight modelspec (example)

`LLM-Configs/lightweight-modelspec.json`

```json
{
  "id": "lightweight-sonnet45-mmlu-pro",
  "mode": "LIGHTWEIGHT",
  "displayName": "Claude Sonnet 4.5 (Replay)",
  "llmProfile": {
    "modelName": "claude-sonnet-4.5",
    "displayName": "Sonnet 4.5"
  },
  "datasetPath": "Datasets/lightweight/sonnet45_mmlu_pro_subset",
  "streaming": {
    "revealDelayMs": 10000,
    "targetTokensPerSecond": 120,
    "burstMultiplierOnFinal": 5.0,
    "maxBufferedChars": 200000
  }
}
```

This is “model owns set” because `datasetPath` points to a folder that already contains questions + reasoning + answers.

### Premium modelspec (example)

`LLM-Configs/premium-modelspec.json`

```json
{
  "id": "premium-kimi-k2-mmlu-pro",
  "mode": "PREMIUM",
  "displayName": "Kimi K2 Thinking (Live)",
  "llmProfile": {
    "modelName": "kimi-k2-thinking",
    "displayName": "Kimi K2"
  },
  "provider": {
    "providerName": "some-provider",
    "apiUrl": "https://provider.example/v1",
    "apiKey": "ENV:KIMI_API_KEY"
  },
  "questionSetPath": "Datasets/question-sets/mmlu_pro_subset.jsonl",
  "streaming": {
    "revealDelayMs": 10000,
    "targetTokensPerSecond": 80,
    "burstMultiplierOnFinal": 5.0,
    "maxBufferedChars": 200000
  }
}
```

This is “set owns model” because the questions come from `questionSetPath` and the model/provider is attached at runtime.

If you prefer to keep provider secrets only in `config.toml`, you can replace the `provider` object with something like `"providerRef": "primary"` and reuse `AppConfig.llmConfig.primary`, but the file above matches your “premium needs url etc” expectation without adding extra layers.

---

## Dataset formats

### Lightweight dataset (recommended minimal structure)

`Datasets/lightweight/<pack>/manifest.json`

* metadata, counts, optional averages (like average TPS), versioning

`Datasets/lightweight/<pack>/items.jsonl`
Each line is one round item:

```json
{
  "questionId": "uuid",
  "prompt": "....",
  "choices": ["A", "B", "C", "D"],
  "verifierSpec": { "type": "multiple_choice", "correctIndex": 2 },

  "llmReasoning": "full reasoning text ...",
  "llmFinalAnswer": { "type": "multiple_choice", "choiceIndex": 2 },

  "replay": {
    "avgTokensPerSecond": 90,
    "reasoningTokenCount": 900
  }
}
```

You can keep this even simpler if you want: a single `items.jsonl` is enough. The important part is that it contains the full reasoning and the final answer.

### Premium question set

`Datasets/question-sets/<set>.jsonl` contains questions + ground truth, but no model output:

```json
{
  "questionId": "uuid",
  "prompt": "...",
  "choices": ["A", "B", "C", "D"],
  "verifierSpec": { "type": "multiple_choice", "correctIndex": 2 }
}
```

That keeps the question bank consistent across modes.

---

## Proposed package layout (single Gradle module, clean packages)

Keep one module for now, but enforce Clean Architecture by package boundaries:

* `internal/domain/...`
  Entities, value objects, policies (`ScorePolicy`, `HandicapPolicy`)

* `internal/application/port/...`
  Existing ports plus a few new ones:

    * `OpponentSpecRepository` (loads modelspec JSONs)
    * `ReplayDatasetRepository` (reads Lightweight dataset)

* `internal/application/usecase/...`

    * `CreateSessionUseCase`, `JoinSessionUseCase`, `StartRoundUseCase`, `SubmitAnswerUseCase`, etc.
    * `TriggerLlmUseCase` (starts streaming after handicap)

* `internal/application/service/...`
  Small coordination services that are not domain policies:

    * `LlmStreamOrchestrator` (the stream shaper described above)

* `internal/infrastructure/spec/...`

    * JSON parsing + file IO for modelspecs
    * resolves `ENV:` secrets if used

* `internal/infrastructure/llm/...`

    * `LightweightLlmPlayerGatewayImpl`
    * `PremiumLlmPlayerGatewayImpl` (provider client + streaming)

* `internal/infrastructure/game/...`

    * `SessionManager` + `SessionActor`
    * `InMemoryGameEventBusImpl`

* `internal/presentation/ktor/game/...`
  WebSocket route, DTO mapping, subscription lifecycle

This avoids multi-module complexity while still keeping the “direction” correct.

---

## Session actor and streaming wiring (server-side)

To keep concurrency simple and avoid race conditions, use an actor per session:

* `SessionActor` owns all mutable session state.
* External stimuli become commands into the actor channel:

    * player join, start round, submit answer
    * **LLM stream events** (important: the collector should push into the actor, not mutate state directly)

### LLM stream flow in the actor

1. Round starts
2. After handicap, actor triggers LLM:

    * obtains `Flow<LlmStreamEvent>` from `LlmPlayerGateway.streamAnswer(context)`
    * passes it through `LlmStreamOrchestrator.apply(policy, flow)`
3. The orchestrator emits a timed stream:

    * nothing is revealed during `revealDelayMs`
    * after reveal begins, deltas are published at target TPS
    * on final, flush buffered reasoning and publish final immediately
4. Each emitted event becomes a `GameEvent` and goes through `GameEventBus` to WS clients.

This produces the exact “fight” feeling without baking Lightweight assumptions into the UI layer.

---

## Client behavior (minimal expectations)

The client keeps two mutable strings in UI state:

* `reasoningText`: append incoming `llm_reasoning_delta.deltaText`
* `finalAnswer`: set on `llm_final_answer`

If the client reconnects mid-round, the server can either:

* replay from a snapshot (optional), or
* keep it simple and only show new deltas from that point (acceptable for festival).

Keeping it simple is fine initially.

---

## Incremental implementation steps (kept intentionally small)

1. **Add modelspec loader** that reads `LLM-Configs/*.json` from `<run-dir>`
   It returns a list of `OpponentSpec` items.

2. **Implement Lightweight gateway** that reads a dataset item by `questionId` and produces a `Flow<LlmStreamEvent>`.

3. **Implement Premium gateway** that calls a provider and produces a `Flow<LlmStreamEvent>`.
   If the provider does not support reasoning streaming, emit only `FinalAnswer` (the orchestrator still works).

4. **Implement `LlmStreamOrchestrator`** with:

    * delayed reveal
    * target TPS playback
    * burst flush on final
    * buffer caps

5. **Extend WS protocol** so deltas and finals reach the client.

This sequence lets you test the “fight feel” quickly using Lightweight while keeping the design ready for Premium.


---

## Appendix A: Workflow Diagrams

### A.1 Game Session Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          SESSION LIFECYCLE                               │
└─────────────────────────────────────────────────────────────────────────┘

  ┌──────────────┐     ┌───────────────┐     ┌─────────────┐
  │ Create       │────▶│ Select        │────▶│ Join        │
  │ Session      │     │ Opponent      │     │ Session     │
  └──────────────┘     │ (modelspec)   │     └──────┬──────┘
                       └───────────────┘            │
                                                    ▼
  ┌──────────────┐     ┌───────────────┐     ┌─────────────┐
  │ Session      │◀────│ Round N       │◀────│ Start       │
  │ Complete     │     │ Complete      │     │ Round 1     │
  └──────────────┘     └───────────────┘     └─────────────┘
                              ▲                     │
                              │     ┌───────────────┘
                              │     ▼
                       ┌──────┴──────────────────────────────┐
                       │         ROUND LOOP (3 rounds)       │
                       │  ┌────────┐  ┌────────┐  ┌────────┐ │
                       │  │Round 1 │─▶│Round 2 │─▶│Round 3 │ │
                       │  └────────┘  └────────┘  └────────┘ │
                       └─────────────────────────────────────┘
```

### A.2 Single Round Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           SINGLE ROUND FLOW                              │
└─────────────────────────────────────────────────────────────────────────┘

      PLAYER                    SERVER                       LLM
        │                          │                          │
        │   ◀── Question Sent ──   │                          │
        │                          │                          │
        │                          │   ── Trigger LLM ──▶     │
        │                          │      (after handicap)    │
        │                          │                          │
        │   ◀── llm_thinking ──    │   ◀── Stream begins ──   │
        │        (spinner)         │                          │
        │                          │                          │
        │        ┌─────────────────┴─────────────────┐        │
        │        │     REVEAL_DELAY (10s buffer)     │        │
        │        │   [reasoning buffered, hidden]    │        │
        │        └─────────────────┬─────────────────┘        │
        │                          │                          │
        │   ◀── reasoning_delta ── │   ◀── ReasoningChunk ──  │
        │        (visible now)     │        (target TPS)      │
        │                          │                          │
        │   ── Submit Answer ──▶   │                          │
        │                          │   ◀── Final Answer ──    │
        │                          │                          │
        │        ┌─────────────────┴─────────────────┐        │
        │        │   BURST FLUSH remaining buffer    │        │
        │        │      (TPS × burstMultiplier)      │        │
        │        └─────────────────┬─────────────────┘        │
        │                          │                          │
        │   ◀── llm_final_answer ──│                          │
        │                          │                          │
        │   ◀── Round Result ──    │                          │
        ▼                          ▼                          ▼
```

### A.3 LLM Streaming Pipeline

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        LLM STREAMING PIPELINE                            │
└─────────────────────────────────────────────────────────────────────────┘

 ┌───────────────────────────────────────────────────────────────────────┐
 │                         SOURCE LAYER                                   │
 │                                                                        │
 │   ┌──────────────────────┐       ┌──────────────────────┐             │
 │   │     LIGHTWEIGHT      │       │       PREMIUM        │             │
 │   │  LlmPlayerGateway    │       │  LlmPlayerGateway    │             │
 │   ├──────────────────────┤       ├──────────────────────┤             │
 │   │ • Read from dataset  │       │ • Live API call      │             │
 │   │ • items.jsonl        │       │ • Provider streaming │             │
 │   │ • Precomputed output │       │ • Real-time output   │             │
 │   └──────────┬───────────┘       └──────────┬───────────┘             │
 │              │                              │                          │
 │              └──────────┬───────────────────┘                          │
 │                         ▼                                              │
 │               Flow<LlmStreamEvent>                                     │
 │               ┌─────────────────┐                                      │
 │               │ ReasoningChunk  │                                      │
 │               │ FinalAnswer     │                                      │
 │               └────────┬────────┘                                      │
 └────────────────────────┼───────────────────────────────────────────────┘
                          ▼
 ┌───────────────────────────────────────────────────────────────────────┐
 │                         SHAPER LAYER                                   │
 │                                                                        │
 │                  ┌─────────────────────────┐                           │
 │                  │   LlmStreamOrchestrator │                           │
 │                  ├─────────────────────────┤                           │
 │                  │ Policy:                 │                           │
 │                  │ • revealDelayMs: 10000  │                           │
 │                  │ • targetTPS: 80-120     │                           │
 │                  │ • burstMultiplier: 5.0  │                           │
 │                  │ • maxBufferedChars      │                           │
 │                  └───────────┬─────────────┘                           │
 │                              │                                         │
 │          ┌───────────────────┴───────────────────┐                     │
 │          ▼                                       ▼                     │
 │   ┌─────────────┐                        ┌─────────────┐               │
 │   │ BUFFER      │──(after delay)──▶      │ EMIT        │               │
 │   │ reasoning   │                        │ at target   │               │
 │   └─────────────┘                        │ TPS rate    │               │
 │          │                               └──────┬──────┘               │
 │          │ on FinalAnswer                       │                      │
 │          ▼                                      ▼                      │
 │   ┌─────────────┐                     Flow<LlmStreamEvent>             │
 │   │ BURST FLUSH │                       (timed/shaped)                 │
 │   │ remaining   │─────────────────────────────▶│                       │
 │   └─────────────┘                              │                       │
 └────────────────────────────────────────────────┼───────────────────────┘
                                                  ▼
 ┌───────────────────────────────────────────────────────────────────────┐
 │                       DELIVERY LAYER                                   │
 │                                                                        │
 │   ┌─────────────────────┐                                              │
 │   │    SessionActor     │──▶ Maps to GameEvent                         │
 │   └──────────┬──────────┘                                              │
 │              ▼                                                         │
 │   ┌─────────────────────┐                                              │
 │   │    GameEventBus     │──▶ Publishes events                          │
 │   └──────────┬──────────┘                                              │
 │              ▼                                                         │
 │   ┌─────────────────────┐     ┌─────────────────────────────┐         │
 │   │  WebSocket Route    │───▶│  Client UI                  │         │
 │   │  (presentation)     │     │  • reasoningText (append)   │         │
 │   │                     │     │  • finalAnswer (set once)   │         │
 │   └─────────────────────┘     └─────────────────────────────┘         │
 │                                                                        │
 │   Events:                                                              │
 │   ├── llm_reasoning_delta(sessionId, roundId, deltaText, seq)         │
 │   └── llm_final_answer(sessionId, roundId, answer)                    │
 └───────────────────────────────────────────────────────────────────────┘
```

### A.4 Mode Comparison

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        MODE COMPARISON                                   │
└─────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────┐     ┌─────────────────────────────┐
  │        LIGHTWEIGHT          │     │          PREMIUM            │
  ├─────────────────────────────┤     ├─────────────────────────────┤
  │                             │     │                             │
  │  "Model owns problem set"   │     │  "Problem set owns model"   │
  │                             │     │                             │
  │  ┌───────────────────────┐  │     │  ┌───────────────────────┐  │
  │  │ lightweight-spec.json │  │     │  │  premium-spec.json    │  │
  │  │ ├─ datasetPath ───────┼──┼─┐   │  │  ├─ questionSetPath ──┼──┼──┐
  │  │ └─ llmProfile         │  │ │   │  │  ├─ provider (live)   │  │  │
  │  └───────────────────────┘  │ │   │  │  └─ llmProfile        │  │  │
  │                             │ │   │  └───────────────────────┘  │  │
  │  ┌───────────────────────┐  │ │   │                             │  │
  │  │ Datasets/lightweight/ │◀─┼─┘   │  ┌───────────────────────┐  │  │
  │  │ └─ items.jsonl        │  │     │  │ Datasets/question-    │◀─┼──┘
  │  │    ├─ question        │  │     │  │   sets/*.jsonl        │  │
  │  │    ├─ llmReasoning ✓  │  │     │  │    ├─ question        │  │
  │  │    ├─ llmFinalAnswer✓ │  │     │  │    ├─ verifierSpec    │  │
  │  │    └─ replay metadata │  │     │  │    └─ (no LLM output) │  │
  │  └───────────────────────┘  │     │  └───────────────────────┘  │
  │                             │     │                             │
  │  ✓ Offline / No API cost    │     │  ✓ Live LLM interaction    │
  │  ✓ Deterministic replays    │     │  ✓ Real reasoning          │
  │  ✗ No dynamic responses     │     │  ✗ Requires API key        │
  │                             │     │                             │
  └─────────────────────────────┘     └─────────────────────────────┘

                    │                            │
                    └────────────┬───────────────┘
                                 ▼
                    ┌─────────────────────────┐
                    │  Same streaming policy  │
                    │  Same shaping behavior  │
                    │  Same client protocol   │
                    └─────────────────────────┘
```
