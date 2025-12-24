    ## What we’re building

A small real-time “race” game: a human and an LLM answer the same benchmark questions in parallel, with a handicap window that gives the human a head start. Each session has 3 questions. The backend must be authoritative (clients never receive ground-truth answers until the round ends), support a Lightweight mode (precomputed LLM outputs) and a Premium mode (live API calls), and keep a simple winners list. Ktor + Kotlin + Dagger + KSP is a great fit.

One important practical note: WebSockets prevent trivial “open DevTools → see answers” only if the server never sends answers early. You can’t stop a player from reading the question (they must), but you can stop them from obtaining the correct answer or forging timing/score by keeping all verification, timers, and scoring on the server.

---

## Goals and constraints

**Functional goals**

* Create/join a session, run 3 rounds, show real-time LLM “progress” + final answers, and compute who wins.
* Support multiple answer types: multiple-choice now, later integer (0–100), later free-response (judgeable via pluggable verifier).
* Lightweight mode uses preloaded benchmark questions + precomputed LLM thoughts/answers.
* Premium mode makes real API calls at runtime.
* Persist minimal results for a winners list.

**Non-goals (for festival version)**

* Full account system, anti-cheat beyond server authority, multi-region latency optimization, multi-instance orchestration.

**Scale**

* DAU ≤ 50, likely single server instance is fine, which simplifies session state (in-memory) dramatically.

---

## System overview (Clean Architecture)

Clean Architecture works nicely if you treat “game” logic as the core, and everything else (Ktor, DB, LLM APIs) as plug-ins.

### Layering

**1) Domain (Enterprise rules)**

* Pure Kotlin, no frameworks.
* Entities/value objects: `GameSession`, `Round`, `Question`, `Player`, `Submission`, `Score`, `Answer`, `TimerSpec`.
* Policies: handicap computation, scoring rules, round lifecycle invariants.

**2) Application (Use cases)**

* Interactors orchestrate domain objects and call ports (interfaces).
* Use cases like `CreateSession`, `JoinSession`, `StartNextRound`, `SubmitAnswer`, `TickTimers`, `EndSession`, `GetLeaderboard`.
* Defines ports: repositories, clock, RNG, LLM gateway, verifier, event publisher.

**3) Interface Adapters**

* Ktor controllers/handlers that translate WS/HTTP DTOs ↔ use case requests/responses.
* Presenters (mapping to client event messages).
* Implementations of ports that still aren’t “infrastructure heavy” (e.g., mapping DB rows to domain models can live here or in infra depending on taste).

**4) Infrastructure**

* Ktor server wiring, WebSocket sessions, DB implementation, migrations, OpenAI/Anthropic client, Redis (optional), logging/metrics.
* Dagger graph assembly.

A useful mental model is: **Domain/Application must compile without Ktor or any LLM SDK present**.

---

## Core domain model

### Session and round

A `GameSession` has:

* `sessionId: Uuid`
* `mode: GameMode` (`LIGHTWEIGHT`, `PREMIUM`, later `SUPER_PREMIUM`)
* `llmProfile: LlmProfile` (model name, temperature, etc.)
* `players: PlayerSet` (human + “LLM player”)
* `rounds: List<Round>` where each round has a `questionRef`, timers, submissions, and result.
* `state: SessionState` (WAITING, IN_PROGRESS, COMPLETED)

A `Round` has:

* `roundId: Uuid`
* `question: Question` (domain object that includes prompt, choices if any, metadata like difficulty)
* `releasedAt: Instant`
* `handicap: Duration` (human head start)
* `deadline: Instant`
* `humanSubmission: Submission?`
* `llmSubmission: Submission?`
* `result: RoundResult?`

### Question and answers

Represent answer as a sealed type so verification stays polymorphic:

* `Answer.MultipleChoice(choiceIndex: Int)`
* `Answer.Integer(value: Int)` (with constraints defined per question, e.g., 0–100)
* `Answer.FreeText(text: String)` (future)

The `Question` carries a `VerifierSpec` that tells the application which verifier to use, without hard-coding the verifier inside the entity.

### Scoring

Given it’s a “race,” scoring typically uses:

* correctness (binary or partial later),
* time-to-submit from `releasedAt` (server clock),
* tie-breakers (earliest correct wins, else both wrong → fastest wrong or no winner).

Keep the scoring logic as a domain policy: `ScorePolicy.compute(round)`.

---

## Pluggable verification (future-proofing)

Define a port:

```kotlin
interface AnswerVerifier {
  fun verify(question: Question, submission: Submission): VerificationOutcome
}
```

Then implement strategies:

* `MultipleChoiceVerifier` (matches correct index)
* `IntegerRangeVerifier` (exact match; enforces bounds like 0–100)
* Later `FreeResponseVerifier` (could be rubric-based, embedding similarity, or “LLM-as-judge” behind a separate safety/abuse gate)

The game engine never needs to know *how* verification works; it only consumes `VerificationOutcome(correct: Boolean, score: Double?)`.

---

## LLM opponent abstraction (Lightweight vs Premium)

Define another port:

```kotlin
interface LlmPlayerGateway {
  suspend fun getAnswer(roundContext: RoundContext): LlmAnswer
}
```

Where `LlmAnswer` can include:

* `finalAnswer: Answer`
* optional `reasoningSummary: String` (be careful about leaking benchmark solutions if you care about spoiler culture)
* optional streaming events (see below)

Two concrete implementations:

1. **LightweightLlmPlayerGateway**
   Looks up precomputed outputs by `(questionId, llmProfile)` and returns them. You can also simulate latency by scheduling “thinking” events for fun.

2. **ApiLlmPlayerGateway**
   Calls the real API. It should be wrapped with:

   * timeouts,
   * retry/backoff for transient failures,
   * a strict budget limit per session/day (festival safety),
   * response parsing into your `Answer` types.

### Handicap behavior

The cleanest approach is to **release the question to the human immediately**, then trigger the LLM attempt after `handicap` elapses. That makes the concept intuitive and easy to enforce server-side. In Lightweight mode, you still delay the “LLM answered” event until handicap passes (even though you already know it).

---

## Real-time transport design (WebSocket-first, REST for misc)

### Why WS is the “source of truth”

All time and scoring should be computed on the server. The client UI can show countdowns, but must treat server events as authoritative. This keeps the game fair even if someone modifies their browser clock or sends crafted messages.

### Connection model

* Client opens a WS to `/ws/game`.
* Server assigns/reads a `clientId` cookie (anonymous) and binds it to a `playerId` inside the session.
* Session join uses a short `joinCode` (e.g., base32) plus the server-created `sessionId`.

### Event protocol

Use a small typed event system. For example:

Client → Server:

* `CreateSession(mode, llmProfile, difficultyPreset?)`
* `JoinSession(joinCode, nickname)`
* `StartSession` (if you want a host)
* `SubmitAnswer(roundId, answerPayload, clientSentAt?, nonceToken)`
* `Ping(seq)` (optional, for latency display)

Server → Client:

* `SessionCreated(sessionId, joinCode)`
* `SessionState(snapshot)`
* `RoundStarted(roundId, questionPayload, releasedAt, handicapMs, deadlineAt, nonceToken)`
* `LlmThinking(roundId, progress?)` (optional cosmetic)
* `LlmSubmitted(roundId)` / `LlmAnswerRevealed(roundId, llmAnswer)` (depending on your spoiler preference)
* `RoundResult(roundId, correctAnswer, humanOutcome, llmOutcome, scores)`
* `SessionCompleted(finalScores, leaderboardDelta?)`
* `Error(code, message)`

The `nonceToken` is important: it’s a per-round server-generated token tied to that connection/session so you can reject forged submissions for other rounds/sessions.

### “Don’t leak answers” rule

Until the round is finished, the server sends only:

* the question (and choices),
* timing info,
* maybe the LLM “is thinking” status.

It never sends:

* the correct answer,
* the LLM final answer (unless you want to show it live; but that invites copying).

You can reveal both after the human submits or time expires.

---

## Use cases (application layer)

At minimum, these interactors keep your system modular:

* `CreateSessionUseCase`: chooses mode/profile, allocates session state, generates join code.
* `JoinSessionUseCase`: attaches a player connection and nickname, returns snapshot.
* `StartSessionUseCase`: selects 3 questions via `QuestionBank`, initializes Round 1.
* `SubmitAnswerUseCase`: validates nonce, locks the submission, computes server submit time.
* `ResolveRoundUseCase`: once human submitted or deadline reached, triggers verification and scoring, emits results.
* `TriggerLlmUseCase`: after handicap, requests LLM answer via gateway (or looks up precomputed), records it.
* `EndSessionUseCase`: persists summary result, updates leaderboard.

A small but valuable pattern is introducing a `GameEventBus` port so your use cases can emit domain events (`RoundStarted`, `RoundResolved`, etc.) and the WS adapter simply subscribes and forwards them to connected clients.

---

## Data persistence (keep it light)

For festival scale and easy deploy, you have three good options:

1. **SQLite** (file-based): easiest operationally, but on some hosts ephemeral disk resets can wipe data. Great if you don’t care about persistence.
2. **Postgres on Render**: very standard, persists winners list.
3. **In-memory + periodic JSON dump**: simplest code, but fragile.

A reasonable compromise is Postgres (or even Render’s managed Postgres) with only a few tables.

### Minimal schema

* `question_bank` (optional if you store questions as JSON in resources)
* `session_results`:

  * sessionId, createdAt, mode, llmProfile, humanNickname, humanScore, llmScore, won(boolean), durationMs
* `leaderboard_entries` (or derive from results with a query):

  * dateBucket, nickname, bestScore, bestTimeMs

Keep question content out of DB if you want; store static JSON for MMLU-Pro subsets and load at startup. For Lightweight mode with precomputed LLM outputs, you can store those alongside the questions in resources or in a separate table/file keyed by `questionId`.

---

## Question bank strategy

Create a `QuestionBank` port:

```kotlin
interface QuestionBank {
  fun pickQuestions(count: Int, constraints: QuestionConstraints): List<Question>
}
```

Implementation options:

* `ResourceJsonQuestionBank` reading `resources/questions/*.json`
* Later `DbQuestionBank`

For “not memorized as a set,” what matters most is rotating and having enough items; per-session UUIDs don’t prevent memorization if the underlying question text repeats, but they do help with protocol integrity. If you can’t increase the dataset, consider mixing in randomized parameter variants for math-style questions where possible.

---

## Package/module structure (Gradle multi-module recommended)

A clean split that maps directly to architecture:

* `:domain`

  * entities, value objects, policies
* `:application`

  * use cases, ports, DTOs (use-case request/response models), domain events
* `:adapters`

  * ktor handlers, websocket protocol mapping, presenters
* `:infrastructure`

  * db implementations, migrations, LLM API client impls, config/env, logging
* `:app`

  * Ktor entrypoint, Dagger component assembly, module wiring

This structure makes it hard to accidentally couple domain logic to Ktor, and it keeps testing pleasant.

---

## Dependency injection with Dagger + KSP

Use Dagger to bind ports to implementations at the outer layers:

* Bind `QuestionBank` → `ResourceJsonQuestionBank` (or DB version)
* Bind `LlmPlayerGateway` → either lightweight or API gateway based on `mode`
* Bind `ResultsRepository` → `ExposedResultsRepository` / `JdbiResultsRepository` / plain JDBC, etc.
* Bind `Clock` → `SystemClock` (and a fake clock for tests)

The important part is that use cases depend on **interfaces** (ports), and Dagger wires concrete classes only in `:app`/`:infrastructure`.

---

## Concurrency model (Ktor + coroutines)

* Each WS connection runs in a coroutine context.
* Game session state should be protected. For a single-instance festival server, a pragmatic pattern is:

  * store sessions in a `ConcurrentHashMap<SessionId, SessionActor>`
  * each `SessionActor` has a `Channel<Command>` and processes sequentially (actor model), ensuring no race conditions when submissions and LLM answers arrive simultaneously.
* Timeouts/deadlines are scheduled inside the session actor (using `delay`), not on clients.

This actor approach makes correctness much easier than sprinkling locks.

---

## Observability and safety

Even for a festival project, you’ll thank yourself for:

* structured logs (sessionId, roundId, playerId),
* request/WS event counters,
* a hard cap on premium API usage (per IP and per day) to avoid surprise bills,
* graceful degradation: if LLM API fails, declare the LLM forfeited for that round or fall back to lightweight answers.

---

## Deployment to Render (simple path)

A practical deployment shape:

* Build a Docker image for the Ktor server.
* Configure environment variables:

  * `PORT`, `DATABASE_URL`, `LLM_API_KEY`, `MODE_DEFAULT`, etc.
* If you use Postgres, add managed Postgres and run migrations on startup (Flyway is straightforward).
* Keep a single instance to avoid multi-instance session routing issues. If you later scale horizontally, you’ll want Redis (or another shared store) for session state and a sticky-session load balancer.

---

## Testing strategy (fast and architecture-aligned)

* Domain tests: scoring, handicap, round lifecycle.
* Use case tests: submission handling, timeouts, verification dispatch.
* Adapter tests: WS message decoding/encoding and error mapping.
* Infrastructure tests: DB repository integration and LLM gateway parsing (with recorded fixtures).

Because the domain/application layers don’t depend on Ktor, most tests are just plain Kotlin/JUnit and run very fast.