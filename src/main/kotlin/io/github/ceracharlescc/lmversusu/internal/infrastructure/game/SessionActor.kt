@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.application.port.*
import io.github.ceracharlescc.lmversusu.internal.application.service.LlmStreamOrchestrator
import io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase
import io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.entity.*
import io.github.ceracharlescc.lmversusu.internal.domain.policy.ScorePolicy
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.RoundResolveReason
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamSeq
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.selects.select
import org.slf4j.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal class SessionActor(
    private val logger: Logger,
    private val sessionId: Uuid,
    private val opponentSpec: OpponentSpec,
    private val gameEventBus: GameEventBus,
    private val startRoundUseCase: StartRoundUseCase,
    private val submitAnswerUseCase: SubmitAnswerUseCase,
    private val answerVerifier: AnswerVerifier,
    private val llmPlayerGateway: LlmPlayerGateway,
    private val llmStreamOrchestrator: LlmStreamOrchestrator,
    private val resultsRepository: ResultsRepository,
    private val clock: Clock,
    private val mailboxCapacity: Int,
    private val onTerminate: (Uuid) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        private const val CLEANUP_GRACE_PERIOD_MS = 60_000L
        private const val EVENT_QUEUE_CAPACITY_MULTIPLIER = 4
        private const val START_ROUND_DEDUP_CAPACITY = 64
        private const val SUBMIT_ANSWER_DEDUP_CAPACITY = 256
    }

    val opponentSpecId: String = opponentSpec.id
    val mode: GameMode = opponentSpec.mode

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mailbox = Channel<SessionCommand>(mailboxCapacity)
    private val internalQueue = Channel<SessionCommand>(mailboxCapacity)
    private val criticalQueue = Channel<SessionCommand>(mailboxCapacity)
    private val eventQueue = Channel<GameEvent>(
        (mailboxCapacity * EVENT_QUEUE_CAPACITY_MULTIPLIER).coerceAtLeast(1)
    )
    private val llmJobs = ConcurrentHashMap<Uuid, Job>()
    private val roundDeadlineJobs = ConcurrentHashMap<Uuid, Job>()
    private var session: GameSession? = null

    private data class RoundStreamState(
        val fullReasoningBuilder: StringBuilder = StringBuilder(),
        var nextReasoningSeq: StreamSeq = StreamSeq(0),
        var lockInEmitted: Boolean = false,
        var getWithheldReasoning: (suspend () -> String)? = null,

        var pendingFinalAnswer: LlmAnswer? = null,
        var finalAnswerPublished: Boolean = false,
        var reasoningRevealPublished: Boolean = false,
    )

    private val roundStreamStates = ConcurrentHashMap<Uuid, RoundStreamState>()

    private var sessionResolvedEmitted: Boolean = false
    private var droppedReasoningDeltaCount: Int = 0
    private val startRoundCommandIds = LruSet<Uuid>(START_ROUND_DEDUP_CAPACITY)
    private val submitAnswerCommandIdsByRound = HashMap<Uuid, LruSet<Uuid>>()

    init {
        scope.launch {
            drainInternalQueues()
        }
        scope.launch {
            drainEvents()
        }
        scope.launch {
            for (command in mailbox) {
                runCatching { handle(command) }
                    .onFailure { throwable ->
                        logger.error("Session actor failed while handling {}", command::class.simpleName, throwable)
                    }
            }
        }
    }

    fun submit(command: SessionCommand): Boolean {
        return mailbox.trySend(command).isSuccess
    }

    suspend fun submitCritical(command: SessionCommand) {
        submitToInternalQueue(criticalQueue, command)
    }

    private suspend fun submitInternal(command: SessionCommand) {
        submitToInternalQueue(internalQueue, command)
    }

    /**
     * Cancels the actor's coroutine scope and closes the mailbox channel.
     * After calling this, the actor should be removed from the SessionManager.
     */
    fun shutdown() {
        criticalQueue.close()
        internalQueue.close()
        eventQueue.close()
        mailbox.close()
        scope.cancel()
    }

    private suspend fun submitToInternalQueue(queue: Channel<SessionCommand>, command: SessionCommand) {
        runCatching { queue.send(command) }
            .onFailure { throwable ->
                logger.warn("Failed to enqueue internal command for session {}", sessionId, throwable)
            }
    }

    private suspend fun drainInternalQueues() {
        try {
            while (currentCoroutineContext().isActive) {
                val command = criticalQueue.tryReceive().getOrNull()
                    ?: select {
                        criticalQueue.onReceive { it }
                        internalQueue.onReceive { it }
                    }

                val sent = runCatching { mailbox.send(command) }
                if (sent.isFailure) {
                    logger.warn("Failed to forward internal command for session {}", sessionId, sent.exceptionOrNull())
                    return
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // Graceful shutdown: channels were closed via shutdown()
            logger.debug("Internal queue drained for session {} (channel closed)", sessionId)
        }
    }

    private suspend fun drainEvents() {
        for (event in eventQueue) {
            runCatching { gameEventBus.publish(event) }
                .onFailure { throwable ->
                    logger.warn("Failed to publish event for session {}", sessionId, throwable)
                }
        }
    }

    private sealed interface EnqueueResult {
        data object Success : EnqueueResult
        data object Dropped : EnqueueResult
        data object Failed : EnqueueResult
    }

    private suspend fun enqueueEvent(event: GameEvent, droppable: Boolean = false): EnqueueResult {
        if (eventQueue.trySend(event).isSuccess) return EnqueueResult.Success

        // Drop reasoning deltas if listeners are slow to keep the actor responsive.
        if (droppable) {
            droppedReasoningDeltaCount += 1
            if (droppedReasoningDeltaCount % 100 == 1) {
                logger.debug(
                    "Dropped {} LLM reasoning delta events for session {}",
                    droppedReasoningDeltaCount,
                    sessionId
                )
            }
            return EnqueueResult.Dropped
        }

        // Suspending send for strict ordering of non-droppable events
        return runCatching { eventQueue.send(event) }
            .fold(
                onSuccess = { EnqueueResult.Success },
                onFailure = { throwable ->
                    logger.warn("Failed to enqueue event for session {}", sessionId, throwable)
                    EnqueueResult.Failed
                }
            )
    }

    private suspend fun handle(command: SessionCommand) {
        when (command) {
            is SessionCommand.JoinSession -> handleJoin(command)
            is SessionCommand.StartNextRound -> handleStartNextRound(command)
            is SessionCommand.SubmitAnswer -> handleSubmitAnswer(command)
            is SessionCommand.StartLlmForRound -> handleStartLlm(command)
            is SessionCommand.LlmStreamReady -> handleLlmStreamReady(command)
            is SessionCommand.LlmReasoningDeltaReceived -> handleLlmReasoningDelta(command)
            is SessionCommand.LlmReasoningTruncatedReceived -> handleLlmReasoningTruncated(command)
            is SessionCommand.LlmReasoningEndedReceived -> handleLlmReasoningEnded(command)
            is SessionCommand.LlmFinalAnswerReceived -> handleLlmFinalAnswer(command)
            is SessionCommand.LlmStreamErrored -> handleLlmStreamError(command)
            is SessionCommand.Timeout -> handleTimeout(command)
            is SessionCommand.RoundDeadlineReached -> handleRoundDeadlineReached(command.roundId)
        }
    }

    private suspend fun handleJoin(command: SessionCommand.JoinSession) {
        if (session == null) {
            val human = Player(
                playerId = command.playerId,
                type = Player.PlayerType.HUMAN,
                nickname = command.nickname,
            )
            val llm = Player(
                playerId = Uuid.random(),
                type = Player.PlayerType.LLM,
                nickname = opponentSpec.displayName,
            )
            val players = PlayerSet(human = human, llm = llm)
            val joinCode = buildJoinCode(sessionId)
            val created = GameSession(
                sessionId = sessionId,
                joinCode = joinCode,
                mode = opponentSpec.mode,
                llmProfile = opponentSpec.llmProfile,
                players = players,
                createdAt = clock.instant()
            )
            session = created

            gameEventBus.authorizePlayer(sessionId, human.playerId)

            enqueueEvent(GameEvent.SessionCreated(sessionId = sessionId, joinCode = joinCode))
            enqueueEvent(
                GameEvent.PlayerJoined(
                    sessionId = sessionId,
                    playerId = human.playerId,
                    nickname = human.nickname,
                )
            )
            enqueueEvent(
                GameEvent.PlayerJoined(
                    sessionId = sessionId,
                    playerId = llm.playerId,
                    nickname = llm.nickname,
                )
            )
            command.response.complete(JoinResponse.Accepted(roundSnapshot = null))
            return
        }

        val existingSession = session ?: return
        if (existingSession.players.human.playerId != command.playerId) {
            // Return error directly via response channel instead of broadcasting to session bus
            command.response.complete(
                JoinResponse.Rejected(
                    errorCode = "session_taken",
                    message = "session already has a different human player",
                )
            )
        } else {
            // Same player rejoining - allowed
            // Build a snapshot of the current round if one is in progress
            val roundSnapshot = buildRoundSnapshot(existingSession)
            command.response.complete(JoinResponse.Accepted(roundSnapshot = roundSnapshot))
        }
    }

    private suspend fun handleStartNextRound(command: SessionCommand.StartNextRound) {
        val currentSession = session
            ?: return publishError("session_not_ready", "session not initialized")

        if (startRoundCommandIds.contains(command.commandId)) return

        if (currentSession.players.human.playerId != command.playerId) {
            return publishError("forbidden", "player cannot start round")
        }

        // Semantic idempotency: round already in progress → no-op success
        if (currentSession.rounds.any { it.isInProgress }) {
            startRoundCommandIds.add(command.commandId)
            return
        }

        when (val result = startRoundUseCase.execute(currentSession, opponentSpec)) {
            is StartRoundUseCase.Result.Success -> {
                startRoundCommandIds.add(command.commandId)
                session = result.session
                val round = result.round
                val expectedAnswerType = when {
                    round.question.choices != null -> "multiple_choice"
                    round.question.verifierSpec is VerifierSpec.IntegerRange -> "integer"
                    else -> "free_text"
                }
                enqueueEvent(
                    GameEvent.RoundStarted(
                        sessionId = sessionId,
                        questionId = round.question.questionId,
                        roundId = round.roundId,
                        roundNumber = result.roundNumber,
                        questionPrompt = round.question.prompt,
                        choices = round.question.choices,
                        expectedAnswerType = expectedAnswerType,
                        releasedAt = round.releasedAt,
                        handicapMs = round.handicap.toMillis(),
                        deadlineAt = round.deadline,
                        nonceToken = round.nonceToken,
                    )
                )
                scheduleLlmStart(round)
                scheduleRoundDeadline(round)
            }

            is StartRoundUseCase.Result.Failure -> {
                publishError(result.errorCode, result.message)
            }
        }
    }

    private suspend fun handleSubmitAnswer(command: SessionCommand.SubmitAnswer) {
        val currentSession = session
            ?: return publishError("session_not_ready", "session not initialized")

        if (isDuplicateSubmitAnswer(command.roundId, command.commandId)) return

        // Semantic idempotency: treat "already submitted" as no-op success
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId }
        if (round != null) {
            val isHuman = currentSession.players.human.playerId == command.playerId
            val isLlm = currentSession.players.llm.playerId == command.playerId
            val alreadySubmitted = when {
                isHuman -> round.humanSubmission != null
                isLlm -> round.llmSubmission != null
                else -> false
            }
            if (alreadySubmitted) {
                recordSubmitAnswerCommand(command.roundId, command.commandId)
                return // No-op: submission already exists
            }
        }

        when (
            val result = submitAnswerUseCase.execute(
                session = currentSession,
                playerId = command.playerId,
                roundId = command.roundId,
                nonceToken = command.nonceToken,
                answer = command.answer,
                clientSentAt = command.clientSentAt,
            )
        ) {
            is SubmitAnswerUseCase.Result.Success -> {
                recordSubmitAnswerCommand(command.roundId, command.commandId)
                session = result.session
                enqueueEvent(
                    GameEvent.SubmissionReceived(
                        sessionId = sessionId,
                        roundId = command.roundId,
                        playerType = result.playerType,
                    )
                )
                if (result.playerType == Player.PlayerType.HUMAN) {
                    publishFinalAnswerIfNeeded(command.roundId)
                }
                resolveRoundIfReady(command.roundId)
            }

            is SubmitAnswerUseCase.Result.Failure -> {
                publishError(result.errorCode, result.message)
            }

        }

    }

    private fun getSubmitAnswerDedupSet(roundId: Uuid): LruSet<Uuid> {
        return submitAnswerCommandIdsByRound.getOrPut(roundId) {
            LruSet(SUBMIT_ANSWER_DEDUP_CAPACITY)
        }
    }

    private fun isDuplicateSubmitAnswer(roundId: Uuid, commandId: Uuid): Boolean {
        return getSubmitAnswerDedupSet(roundId).contains(commandId)
    }

    private fun recordSubmitAnswerCommand(roundId: Uuid, commandId: Uuid) {
        getSubmitAnswerDedupSet(roundId).add(commandId)
    }

    private suspend fun handleStartLlm(command: SessionCommand.StartLlmForRound) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return

        if (llmJobs.containsKey(command.roundId)) return
        logger.debug("Starting LLM for session {}, round {}", sessionId, command.roundId)
        enqueueEvent(
            GameEvent.LlmThinking(
                sessionId = sessionId,
                roundId = command.roundId,
                progress = null,
            )
        )

        roundStreamStates.computeIfAbsent(command.roundId) { RoundStreamState() }

        val job = scope.launch {
            runCatching {
                val expectedKind = when {
                    round.question.choices != null -> ExpectedAnswerKind.MULTIPLE_CHOICE
                    round.question.verifierSpec is VerifierSpec.IntegerRange -> ExpectedAnswerKind.INTEGER
                    else -> ExpectedAnswerKind.FREE_TEXT
                }

                val roundContext = RoundContext(
                    questionId = round.question.questionId,
                    questionPrompt = round.question.prompt,
                    choices = round.question.choices,
                    expectedAnswerKind = expectedKind,
                    opponentSpec = opponentSpec,
                )

                val upstream = llmPlayerGateway.streamAnswer(roundContext)
                val orchestrationResult = llmStreamOrchestrator.applyWithReveal(opponentSpec.streaming, upstream)

                submitInternal(
                    SessionCommand.LlmStreamReady(
                        roundId = round.roundId,
                        getWithheldReasoning = orchestrationResult.getWithheldReasoning,
                    )
                )

                orchestrationResult.events.collect { event ->
                    when (event) {
                        is LlmStreamEvent.ReasoningDelta -> {
                            submitInternal(
                                SessionCommand.LlmReasoningDeltaReceived(
                                    roundId = round.roundId,
                                    deltaText = event.deltaText,
                                )
                            )
                        }

                        is LlmStreamEvent.ReasoningTruncated -> {
                            submitInternal(
                                SessionCommand.LlmReasoningTruncatedReceived(
                                    roundId = round.roundId,
                                    droppedChars = event.droppedChars,
                                )
                            )
                        }

                        is LlmStreamEvent.ReasoningEnded -> {
                            submitInternal(SessionCommand.LlmReasoningEndedReceived(roundId = round.roundId))
                        }

                        is LlmStreamEvent.FinalAnswer -> {
                            submitInternal(
                                SessionCommand.LlmFinalAnswerReceived(
                                    roundId = round.roundId,
                                    answer = event.answer,
                                )
                            )
                        }

                        is LlmStreamEvent.Error -> {
                            submitInternal(
                                SessionCommand.LlmStreamErrored(
                                    roundId = round.roundId,
                                    message = event.message,
                                    cause = event.cause,
                                )
                            )
                        }
                    }
                }
            }.onFailure { throwable ->
                if (throwable !is CancellationException) {
                    submitInternal(
                        SessionCommand.LlmStreamErrored(
                            roundId = round.roundId,
                            message = throwable.message ?: "LLM stream failed",
                            cause = throwable,
                        )
                    )
                }
            }
        }

        job.invokeOnCompletion { llmJobs.remove(command.roundId) }
        llmJobs[command.roundId] = job
    }

    private suspend fun handleLlmStreamReady(command: SessionCommand.LlmStreamReady) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return

        val streamState = roundStreamStates.computeIfAbsent(command.roundId) { RoundStreamState() }
        streamState.getWithheldReasoning = command.getWithheldReasoning
    }

    private suspend fun handleLlmReasoningDelta(command: SessionCommand.LlmReasoningDeltaReceived) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return

        val streamState = roundStreamStates.computeIfAbsent(command.roundId) { RoundStreamState() }
        streamState.fullReasoningBuilder.append(command.deltaText)
        val seq = streamState.nextReasoningSeq
        streamState.nextReasoningSeq = seq.next()

        enqueueEvent(
            GameEvent.LlmReasoningDelta(
                sessionId = sessionId,
                roundId = command.roundId,
                deltaText = command.deltaText,
                seq = seq,
            ),
            droppable = true
        )
    }

    private suspend fun handleLlmReasoningTruncated(command: SessionCommand.LlmReasoningTruncatedReceived) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return

        enqueueEvent(
            GameEvent.LlmReasoningTruncated(
                sessionId = sessionId,
                roundId = command.roundId,
                droppedChars = command.droppedChars,
            )
        )
    }

    private suspend fun handleLlmReasoningEnded(command: SessionCommand.LlmReasoningEndedReceived) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return

        enqueueEvent(
            GameEvent.LlmReasoningEnded(
                sessionId = sessionId,
                roundId = command.roundId,
            )
        )
    }

    private suspend fun handleLlmStreamError(command: SessionCommand.LlmStreamErrored) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return

        logger.error(
            "LLM stream error for session {}, round {}: {}",
            sessionId,
            command.roundId,
            command.message,
            command.cause
        )
        enqueueEvent(
            GameEvent.LlmStreamError(
                sessionId = sessionId,
                roundId = command.roundId,
                message = command.message,
            )
        )
    }

    private suspend fun handleLlmFinalAnswer(command: SessionCommand.LlmFinalAnswerReceived) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return
        val llmPlayerId = currentSession.players.llm.playerId

        val streamState = roundStreamStates.computeIfAbsent(command.roundId) { RoundStreamState() }
        streamState.pendingFinalAnswer = command.answer

        val result = submitAnswerUseCase.execute(
            session = currentSession,
            playerId = llmPlayerId,
            roundId = command.roundId,
            nonceToken = round.nonceToken,
            answer = command.answer.finalAnswer,
            clientSentAt = null,
        )

        if (result !is SubmitAnswerUseCase.Result.Success) return
        session = result.session

        val updatedRound = result.session.rounds.firstOrNull { it.roundId == command.roundId } ?: return

        // If human already submitted, publish final answer now (once)
        if (updatedRound.humanSubmission != null) {
            publishFinalAnswerIfNeeded(command.roundId)
        } else if (!streamState.lockInEmitted) {
            streamState.lockInEmitted = true
            enqueueEvent(GameEvent.LlmAnswerLockIn(sessionId = sessionId, roundId = command.roundId))
        }

        resolveRoundIfReady(command.roundId)
    }

    private suspend fun publishFinalAnswerIfNeeded(roundId: Uuid) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == roundId } ?: return
        val streamState = roundStreamStates[roundId] ?: return
        val pending = streamState.pendingFinalAnswer ?: return

        if (streamState.finalAnswerPublished) return
        if (round.humanSubmission == null) return // hard gate

        val result = enqueueEvent(
            GameEvent.LlmFinalAnswer(
                sessionId = sessionId,
                roundId = roundId,
                answer = pending,
            )
        )
        if (result == EnqueueResult.Success) {
            streamState.finalAnswerPublished = true
        }
    }

    private suspend fun resolveRoundIfReady(roundId: Uuid) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == roundId } ?: return
        if (!round.hasAllSubmissions || round.result != null) return

        val humanSubmission = round.humanSubmission ?: return
        val llmSubmission = round.llmSubmission ?: return

        // Cancel the deadline job since round is resolving normally
        roundDeadlineJobs.remove(roundId)?.cancel()

        val humanOutcome = answerVerifier.verify(round.question, humanSubmission)
        val llmOutcome = answerVerifier.verify(round.question, llmSubmission)
        val correctAnswer = correctAnswerFor(round.question)
        val result = ScorePolicy.compute(
            round = round,
            correctAnswer = correctAnswer,
            humanCorrect = humanOutcome.correct,
            llmCorrect = llmOutcome.correct,
            reason = RoundResolveReason.NORMAL,
        )

        val updatedRound = round.copy(result = result)
        val updatedRounds = currentSession.rounds.map { existing ->
            if (existing.roundId == roundId) updatedRound else existing
        }
        val updatedSession = currentSession.copy(
            rounds = updatedRounds,
            state = if (updatedRounds.all { !it.isInProgress } && updatedRounds.size == GameSession.TOTAL_ROUNDS) {
                SessionState.COMPLETED
            } else {
                SessionState.IN_PROGRESS
            },
        )
        session = updatedSession

        llmJobs.remove(roundId)?.cancel()

        // Emit LLM artifacts before RoundResolved
        emitArtifactsAtRoundEnd(roundId, updatedRound)

        enqueueEvent(
            GameEvent.RoundResolved(
                sessionId = sessionId,
                roundId = roundId,
                correctAnswer = result.correctAnswer,
                humanCorrect = humanOutcome.correct,
                llmCorrect = llmOutcome.correct,
                humanScore = result.humanOutcome.score.points,
                llmScore = result.llmOutcome.score.points,
                winner = result.winner.name,
                reason = result.reason,
            )
        )

        // Clean up stream state after artifacts are emitted
        roundStreamStates.remove(roundId)

        if (updatedSession.isCompleted) {
            val (humanScore, llmScore) = updatedSession.calculateTotalScores()
            val now = Instant.now(clock)

            // Emit SessionResolved immediately for end-of-match display
            emitSessionResolved(
                reason = "completed",
                state = SessionState.COMPLETED,
                resolvedAt = now,
            )

            enqueueEvent(
                GameEvent.SessionCompleted(
                    sessionId = sessionId,
                    humanTotalScore = humanScore.points,
                    llmTotalScore = llmScore.points,
                    humanWon = humanScore.points >= llmScore.points,
                )
            )
            saveSessionResult(updatedSession, updatedRound, humanScore.points, llmScore.points)
            // Schedule self-removal after grace period
            scope.launch {
                delay(CLEANUP_GRACE_PERIOD_MS)
                enqueueEvent(
                    GameEvent.SessionTerminated(
                        sessionId = sessionId,
                        reason = "completed",
                    )
                )
                onTerminate(sessionId)
            }
        }
    }

    private suspend fun saveSessionResult(
        session: GameSession,
        round: Round,
        humanScore: Double,
        llmScore: Double,
    ) {
        val now = Instant.now(clock)
        val durationMs = Duration.between(session.createdAt, now).toMillis()
        val questionSetDisplayName = when (opponentSpec) {
            is OpponentSpec.Lightweight -> opponentSpec.questionSetDisplayName
            is OpponentSpec.Premium -> opponentSpec.questionSetDisplayName
        }
        val result = SessionResult(
            sessionId = session.sessionId,
            gameMode = session.mode,
            difficulty = round.question.difficulty,
            llmProfileName = session.llmProfile.displayName,
            questionSetDisplayName = questionSetDisplayName,
            humanNickname = session.players.human.nickname,
            humanUserId = session.players.human.playerId,
            humanScore = humanScore,
            llmScore = llmScore,
            humanWon = humanScore >= llmScore,
            durationMs = durationMs,
            completedAt = now,
        )
        resultsRepository.saveResult(result)
    }

    private suspend fun publishError(errorCode: String, message: String) {
        enqueueEvent(
            GameEvent.SessionError(
                sessionId = sessionId,
                errorCode = errorCode,
                message = message,
            )
        )
    }

    /**
     * Emits a SessionResolved event exactly once per session.
     * This is the single authoritative terminal summary that clients can rely on.
     */
    private suspend fun emitSessionResolved(
        reason: String,
        state: SessionState,
        resolvedAt: Instant,
    ) {
        if (sessionResolvedEmitted) return

        val currentSession = session ?: return

        val (humanScore, llmScore) = currentSession.calculateTotalScores()
        val roundsPlayed = currentSession.rounds.count { it.result != null }

        val winner = when {
            roundsPlayed == 0 -> GameEvent.MatchWinner.NONE
            humanScore.points > llmScore.points -> GameEvent.MatchWinner.HUMAN
            llmScore.points > humanScore.points -> GameEvent.MatchWinner.LLM
            else -> GameEvent.MatchWinner.TIE
        }

        val durationMs = Duration.between(currentSession.createdAt, resolvedAt).toMillis()

        val result = enqueueEvent(
            GameEvent.SessionResolved(
                sessionId = sessionId,
                state = state,
                reason = reason,
                humanTotalScore = humanScore.points,
                llmTotalScore = llmScore.points,
                winner = winner,
                roundsPlayed = roundsPlayed,
                totalRounds = GameSession.TOTAL_ROUNDS,
                resolvedAt = resolvedAt,
                durationMs = durationMs,
            )
        )

        if (result == EnqueueResult.Success) {
            sessionResolvedEmitted = true
        } else {
            logger.warn("Failed to emit SessionResolved for session {}", sessionId)
        }
    }


    private fun scheduleLlmStart(round: Round) {
        val delayMs = round.handicap.toMillis().coerceAtLeast(0L)
        scope.launch {
            delay(delayMs)
            submitInternal(SessionCommand.StartLlmForRound(roundId = round.roundId))
        }
    }

    /**
     * Schedules a deadline timer for the given round.
     * When the timer fires, the round will be force-resolved if it is still in progress.
     */
    private fun scheduleRoundDeadline(round: Round) {
        roundDeadlineJobs[round.roundId]?.cancel()

        val now = Instant.now(clock)
        val delayMs = Duration.between(now, round.deadline).toMillis().coerceAtLeast(0L)

        roundDeadlineJobs[round.roundId] = scope.launch {
            delay(delayMs)
            submitCritical(SessionCommand.RoundDeadlineReached(roundId = round.roundId))
        }
    }

    /**
     * Handles a round deadline being reached.
     * Force-resolves the round by assigning 0 points to any player who hasn't submitted.
     */
    private suspend fun handleRoundDeadlineReached(roundId: Uuid) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == roundId } ?: return
        if (!round.isInProgress) return

        // If we fired slightly early due to clock issues, reschedule
        val now = Instant.now(clock)
        if (now.isBefore(round.deadline)) {
            scheduleRoundDeadline(round)
            return
        }

        logger.info("Round deadline reached for session {}, round {}", sessionId, roundId)

        val humanMissing = (round.humanSubmission == null)
        val llmMissing = (round.llmSubmission == null)

        val reason = when {
            humanMissing && llmMissing -> RoundResolveReason.TIMEOVER_BOTH
            humanMissing -> RoundResolveReason.TIMEOVER_HUMAN
            llmMissing -> RoundResolveReason.TIMEOVER_LLM
            else -> RoundResolveReason.NORMAL
        }

        llmJobs.remove(roundId)?.cancel()

        val correctAnswer = correctAnswerFor(round.question)

        // Create timeout submissions for missing players
        fun timeoutSubmission(playerId: Uuid): Submission =
            Submission(
                submissionId = Uuid.random(),
                playerId = playerId,
                answer = correctAnswer, // value doesn't matter since we force correct=false
                serverReceivedAt = round.deadline, // makes responseTime == full time
                clientSentAt = null,
            )

        val roundWithTimeoutSubs = round.copy(
            humanSubmission = round.humanSubmission ?: timeoutSubmission(currentSession.players.human.playerId),
            llmSubmission = round.llmSubmission ?: timeoutSubmission(currentSession.players.llm.playerId),
        )

        // Missing players are considered incorrect
        val humanCorrect = if (humanMissing) false
        else answerVerifier.verify(round.question, roundWithTimeoutSubs.humanSubmission!!).correct

        val llmCorrect = if (llmMissing) false
        else answerVerifier.verify(round.question, roundWithTimeoutSubs.llmSubmission!!).correct

        val result = ScorePolicy.compute(
            round = roundWithTimeoutSubs,
            correctAnswer = correctAnswer,
            humanCorrect = humanCorrect,
            llmCorrect = llmCorrect,
            reason = reason,
        )

        val finalizedRound = roundWithTimeoutSubs.copy(result = result)

        val updatedRounds = currentSession.rounds.map { existing ->
            if (existing.roundId == roundId) finalizedRound else existing
        }

        val updatedSession = currentSession.copy(
            rounds = updatedRounds,
            state = if (updatedRounds.size == GameSession.TOTAL_ROUNDS && updatedRounds.all { !it.isInProgress }) {
                SessionState.COMPLETED
            } else {
                SessionState.IN_PROGRESS
            }
        )
        session = updatedSession

        // Cancel deadline job now that round is finalized
        roundDeadlineJobs.remove(roundId)?.cancel()

        // Emit LLM artifacts before RoundResolved
        emitArtifactsAtRoundEnd(roundId, finalizedRound)

        enqueueEvent(
            GameEvent.RoundResolved(
                sessionId = sessionId,
                roundId = roundId,
                correctAnswer = result.correctAnswer,
                humanCorrect = humanCorrect,
                llmCorrect = llmCorrect,
                humanScore = result.humanOutcome.score.points,
                llmScore = result.llmOutcome.score.points,
                winner = result.winner.name,
                reason = result.reason,
            )
        )

        // Clean up stream state after artifacts are emitted
        roundStreamStates.remove(roundId)

        if (updatedSession.isCompleted) {
            val (humanScoreTotal, llmScoreTotal) = updatedSession.calculateTotalScores()
            val now = Instant.now(clock)

            emitSessionResolved(
                reason = "completed",
                state = SessionState.COMPLETED,
                resolvedAt = now,
            )

            enqueueEvent(
                GameEvent.SessionCompleted(
                    sessionId = sessionId,
                    humanTotalScore = humanScoreTotal.points,
                    llmTotalScore = llmScoreTotal.points,
                    humanWon = humanScoreTotal.points >= llmScoreTotal.points,
                )
            )
            saveSessionResult(updatedSession, finalizedRound, humanScoreTotal.points, llmScoreTotal.points)
            scope.launch {
                delay(CLEANUP_GRACE_PERIOD_MS)
                enqueueEvent(
                    GameEvent.SessionTerminated(
                        sessionId = sessionId,
                        reason = "completed",
                    )
                )
                onTerminate(sessionId)
            }
        }
    }

    /**
     * Emits LLM artifacts (FinalAnswer and ReasoningReveal) at round end.
     * This is the single, idempotent routine responsible for ensuring artifacts are delivered.
     *
     * Fallback sources:
     * - FinalAnswer: pendingFinalAnswer ?? reconstruct from round.llmSubmission.answer
     * - ReasoningReveal: fullReasoning ?? reasoningSummary from pendingFinalAnswer
     */
    private suspend fun emitArtifactsAtRoundEnd(roundId: Uuid, round: Round) {
        // Get stream state for idempotency tracking (may be null if LLM never started)
        val streamState = roundStreamStates[roundId]

        // === Emit FinalAnswer ===
        if (streamState?.finalAnswerPublished != true) {
            val answer: LlmAnswer? = streamState?.pendingFinalAnswer
                ?: round.llmSubmission?.let {
                    LlmAnswer(finalAnswer = it.answer, reasoningSummary = null, confidenceScore = null)
                }

            if (answer != null) {
                val result = enqueueEvent(
                    GameEvent.LlmFinalAnswer(
                        sessionId = sessionId,
                        roundId = roundId,
                        answer = answer,
                    )
                )
                if (result == EnqueueResult.Success) {
                    streamState?.let { it.finalAnswerPublished = true }
                }
            }
        }

        // === Emit ReasoningReveal ===
        if (streamState?.reasoningRevealPublished != true) {
            val fullReasoning = buildString {
                streamState?.fullReasoningBuilder?.let { append(it.toString()) }
                runCatching { streamState?.getWithheldReasoning?.invoke().orEmpty() }
                    .onFailure {
                        logger.warn(
                            "Failed to obtain withheld reasoning for session {}, round {}",
                            sessionId,
                            roundId,
                            it
                        )
                    }
                    .getOrDefault("")
                    .let { if (it.isNotEmpty()) append(it) }
            }

            // Fallback to reasoningSummary if no streamed reasoning
            val revealText = fullReasoning.ifEmpty {
                streamState?.pendingFinalAnswer?.reasoningSummary.orEmpty()
            }

            if (revealText.isNotEmpty()) {
                logger.debug("Reasoning reveal for session {}, round {}: {}", sessionId, roundId, revealText)
                val result = enqueueEvent(
                    GameEvent.LlmReasoningReveal(
                        sessionId = sessionId,
                        roundId = roundId,
                        fullReasoning = revealText,
                    )
                )
                if (result == EnqueueResult.Success) {
                    streamState?.let { it.reasoningRevealPublished = true }
                }
            }
        }
    }

    private fun correctAnswerFor(question: Question): Answer =
        when (val spec = question.verifierSpec) {
            is VerifierSpec.MultipleChoice -> Answer.MultipleChoice(spec.correctIndex)
            is VerifierSpec.IntegerRange -> Answer.Integer(spec.correctValue)
            is VerifierSpec.FreeResponse -> Answer.FreeText(spec.expectedKeywords.joinToString(" "))
        }

    private fun buildJoinCode(sessionId: Uuid): String {
        return sessionId.toString()
    }

    /**
     * Builds a RoundStarted snapshot for the currently in-progress round, if any.
     * Used to replay round state when a player reconnects mid-round.
     */
    private fun buildRoundSnapshot(gameSession: GameSession): GameEvent.RoundStarted? {
        val activeRound = gameSession.rounds.lastOrNull { it.isInProgress } ?: return null
        val roundNumber = gameSession.rounds.indexOfFirst { it.roundId == activeRound.roundId } + 1

        val expectedAnswerType = when {
            activeRound.question.choices != null -> "multiple_choice"
            activeRound.question.verifierSpec is VerifierSpec.IntegerRange -> "integer"
            else -> "free_text"
        }

        return GameEvent.RoundStarted(
            sessionId = sessionId,
            questionId = activeRound.question.questionId,
            roundId = activeRound.roundId,
            roundNumber = roundNumber,
            questionPrompt = activeRound.question.prompt,
            choices = activeRound.question.choices,
            expectedAnswerType = expectedAnswerType,
            releasedAt = activeRound.releasedAt,
            handicapMs = activeRound.handicap.toMillis(),
            deadlineAt = activeRound.deadline,
            nonceToken = activeRound.nonceToken,
        )
    }

    private suspend fun handleTimeout(command: SessionCommand.Timeout) {
        val currentSession = session ?: return
        if (currentSession.isCompleted) return

        val now = Instant.now(clock)
        session = currentSession.copy(state = SessionState.CANCELLED)

        llmJobs.values.forEach { it.cancel() }
        llmJobs.clear()
        roundDeadlineJobs.values.forEach { it.cancel() }
        roundDeadlineJobs.clear()
        roundStreamStates.clear()
        submitAnswerCommandIdsByRound.clear()

        // Emit SessionResolved immediately with current scores before termination
        emitSessionResolved(
            reason = command.reason,
            state = SessionState.CANCELLED,
            resolvedAt = now,
        )

        enqueueEvent(
            GameEvent.SessionTerminated(
                sessionId = sessionId,
                reason = command.reason,
            )
        )
        onTerminate(sessionId)
    }
}

internal sealed interface JoinResponse {
    data class Accepted(val roundSnapshot: GameEvent.RoundStarted?) : JoinResponse
    data class Rejected(val errorCode: String, val message: String) : JoinResponse
}

private class LruSet<T>(private val capacity: Int) {
    private val entries = LinkedHashMap<T, Unit>(capacity, 0.75f, true)

    fun add(value: T): Boolean {
        val existed = entries.containsKey(value)
        entries[value] = Unit
        if (entries.size > capacity) {
            val iterator = entries.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return !existed
    }

    fun contains(value: T): Boolean = entries.containsKey(value)
}
