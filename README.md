# LMVersus-U

A small Kotlin/Ktor web app where **you** race an LLM on quiz questions.

- **LIGHTWEIGHT** mode plays against a *pre-recorded replay* (no API calls).
- **PREMIUM** mode plays against a *live* LLM via an *OpenAI-compatible* API.

The twist: the LLM's reasoning is streamed in a game-like way, but the **final portion is held back** and only revealed after the round ends.

---

## Quick start

### Prerequisites

- JDK **21** (the project uses the Kotlin JVM toolchain set to 21)
- Internet access the first time you run Gradle (to download Gradle + dependencies)

### Run locally

From the repo root:

1) (Optional) enable Premium mode by setting the API key used by the default premium modelspec:

```bash
export OPENAI_API_KEY="..."
````

2. Start the server:

```bash
# simplest
./gradlew testRun

# or
./gradlew run
```

3. Open the UI:

* [http://localhost:8080](http://localhost:8080)

> **Port note:** the listener host/port come from `src/main/resources/application.yaml` (and the `PORT` env var).
> `config.toml` currently controls runtime behavior (CORS/debug/etc.) but does **not** change the engine's bind port yet.

---

## Configuration directory

LMVersus-U reads its runtime files from a **config directory**:

* Default: the current working directory
* Override: pass `-Dlmversusu.configDir=/path/to/dir`

That directory is expected to contain:

```text
config.toml
LLM-Configs/
  lightweight-modelspec.json
  premium-modelspec.json
  Datasets/
    questions/...
    models/...
```

Secrets can be injected using the `ENV:` prefix in JSON/TOML fields that are treated as secrets (for example, the premium provider API key):

```json
"apiKey": "ENV:OPENAI_API_KEY"
```

---

## `config.toml`

On first start (if it's missing), the app will copy `src/main/resources/config.default.toml` into your config directory.

What's currently used at runtime:

* `serverConfig.debug` and `serverConfig.corsAllowedHosts` (CORS + websocket origin checks)
* `sessionLimitConfig.*` (per-mode limits and websocket message limits)
* `sessionCrypto.*` (cookie/session crypto)

Some fields exist for future work and aren't wired everywhere yet (see `PLAN.md`): `serverConfig.bindHost/bindPort`, `logConfig`, and most of `rateLimitConfig`.

### Session cookie crypto

LMVersus-U uses encrypted + signed cookies for the player session.

* `encryptionKeyHex`: **16 bytes** (32 hex chars) for AES-128
* `signKeyHex`: **32 bytes** (64 hex chars) for HMAC-SHA256

Generate safe values:

```bash
openssl rand -hex 16  # -> encryptionKeyHex
openssl rand -hex 32  # -> signKeyHex
```

`enableSecureCookie` should be `true` when served over HTTPS (recommended for any real deployment). For plain HTTP local dev, set it to `false` or your browser won't store the cookie.

---

## Opponents (modelspecs)

The UI's opponent dropdowns are backed by two JSON files in `LLM-Configs/`:

* `lightweight-modelspec.json` (mode = `LIGHTWEIGHT`)
* `premium-modelspec.json` (mode = `PREMIUM`)

Each file describes **one** opponent today (kept intentionally simple). The REST endpoint `GET /api/v1/models?mode=...` returns these specs in a public shape.

### Common fields

* `id`: stable identifier used by the UI
* `displayName`: shown in the UI
* `llmProfile`: model name and decoding parameters
* `questionSetPath` / `questionSetDisplayName`: which question set to use
* `streaming`: how the reasoning stream is shaped

### Lightweight-only fields

* `datasetPath`: path to a replay dataset (pre-recorded answers)

### Premium-only fields

* `provider.apiUrl`: base URL (OpenAI-compatible)
* `provider.apiKey`: secret (often `ENV:...`)
* `provider.compat`: selects protocol + structured-output + reasoning behavior
* `provider.extraBody`: additional request fields passed to the provider

#### Provider compatibility knobs

`provider.compat` supports:

* `apiProtocol`: `AUTO | RESPONSES | CHAT_COMPLETIONS`
* `structuredOutput`: `JSON_SCHEMA | JSON_OBJECT | NONE`
* `reasoning`: `AUTO | SUMMARY_ONLY | RAW_REASONING_FIELD | NONE`

This is mainly there so you can point the premium mode at "OpenAI-compatible" backends that differ slightly in how they stream content/reasoning.

### Streaming policy knobs

`streaming` is applied by `LlmStreamOrchestrator` and is deliberately separate from the upstream model source.

Useful fields:

* `revealDelayMs`: delay before showing *any* reasoning
* `targetTokensPerSecond`: pacing
* `burstMultiplierOnFinal`: speed up when the end is near
* `maxBufferedChars`: backpressure/truncation ceiling
* `chunkDelay`: how many reasoning chunks to **withhold** and only reveal at round end

---

## Datasets

### Question sets

A question set directory contains:

```text
manifest.json
questions/
  <questionId>.json
```

`manifest.json` looks like:

```json
{ "setId": "mmlu-pro", "version": 1, "questionIds": ["..."] }
```

Each question file contains (simplified):

* `questionId` (UUID)
* `prompt` (Markdown supported in the UI; KaTeX rendering is enabled client-side)
* `choices` (optional; when present, the round is multiple-choice)
* `difficulty` (`EASY | MEDIUM | HARD`)
* `verifierSpec`:

  * `multiple_choice` (correct index)
  * `integer_range` (correct integer, optional min/max)
  * `free_response` (stored, but verification is currently not implemented)
* `roundTime` (optional; seconds)

### Lightweight replay datasets

A lightweight dataset directory contains:

```text
manifest.json
replays/
  <questionId>.json
```

The manifest links back to a question set and lists the available `questionIds`. Each replay file stores a pre-recorded "LLM answer" for a question.

In lightweight mode, the backend simulates streaming by slicing `reasoningText` into small chunks and pacing them through the same streaming pipeline used by premium mode.

---

## How a match works

The built-in frontend is vanilla JS served from `src/main/resources/web` at `/`.

At a high level:

* The browser requests a player session: `GET /api/v1/player/session` (sets an encrypted cookie).
* It opens a websocket: `ws(s)://<host>/ws/game`.
* It sends `join_session` with an opponent id + nickname.
* The server pushes events like:

  * `session_created`, `player_joined`
  * `round_started`
  * `llm_reasoning_delta` (streamed)
  * `llm_answer_lock_in` (LLM has decided internally)
  * `submission_received`
  * `llm_final_answer` (sent **only after the human submits**)
  * `llm_reasoning_reveal` (withheld tail is revealed)
  * `round_resolved`, `session_resolved`

Important gameplay constraint (enforced server-side): **the LLM's final answer is not sent to the client until the human has submitted**.

The "Leaderboard" is currently **in-memory** and resets when the server restarts.

---

## Architecture (in the spirit of `PLAN.md`)

The repo is organized around a simple clean-architecture shape:

* **Domain**: game entities (rounds/sessions), deterministic policies (scoring/handicap), value objects
* **Application**: use-cases + ports (question bank, LLM gateway, verifier, event bus)
* **Infrastructure**: adapters (file-backed datasets, OpenAI-compatible client, in-memory repositories/event bus)
* **Presentation**: Ktor routes + websocket frame mapping + static web UI

Two design choices from `PLAN.md` show up everywhere:

1. **The model never grades itself.**
   Answer verification and scoring live in deterministic policies (`AnswerVerifierImpl`, `ScorePolicy`).

2. **Model output is a stream, and shaping the stream is its own concern.**
   `LlmPlayerGateway` produces upstream events. `LlmStreamOrchestrator` applies the "game" policy (pacing, truncation, withholding), independent of whether the source is replay or live API.

---

## Development

Helpful commands (from the Gradle build):

```bash
./gradlew test      # unit + contract tests
./gradlew testRun   # run the server from the project directory
```

The main server module is `io.github.ceracharlescc.lmversusu.internal.ApplicationKt.module`.

Coding conventions and project rules live in `AGENTS.md`.

---