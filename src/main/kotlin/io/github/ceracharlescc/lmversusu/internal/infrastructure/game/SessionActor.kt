@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.application.port.AnswerVerifier
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.ExpectedAnswerKind
import io.github.ceracharlescc.lmversusu.internal.application.port.LlmPlayerGateway
import io.github.ceracharlescc.lmversusu.internal.application.port.RoundContext
import io.github.ceracharlescc.lmversusu.internal.application.service.LlmStreamOrchestrator
import io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase
import io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.entity.*
import io.github.ceracharlescc.lmversusu.internal.domain.policy.ScorePolicy
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamSeq
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

internal class SessionActor(
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
    private val onTerminate: (Uuid) -> Unit,
) {
    companion object {
        private const val CLEANUP_GRACE_PERIOD_MS = 60_000L
    }

    val opponentSpecId: String = opponentSpec.id

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mailbox = Channel<SessionCommand>(Channel.UNLIMITED)
    private val llmJobs = ConcurrentHashMap<Uuid, Job>()
    private var session: GameSession? = null

    private data class RoundStreamState(
        val fullReasoningBuilder: StringBuilder = StringBuilder(),
        var lockInEmitted: Boolean = false,
        var getWithheldReasoning: (suspend () -> String)? = null,
    )

    private val roundStreamStates = ConcurrentHashMap<Uuid, RoundStreamState>()

    init {
        scope.launch {
            for (command in mailbox) {
                handle(command)
            }
        }
    }

    fun submit(command: SessionCommand) {
        mailbox.trySend(command)
    }

    /**
     * Cancels the actor's coroutine scope and closes the mailbox channel.
     * After calling this, the actor should be removed from the SessionManager.
     */
    fun shutdown() {
        mailbox.close()
        scope.cancel()
    }

    private suspend fun handle(command: SessionCommand) {
        when (command) {
            is SessionCommand.JoinSession -> handleJoin(command)
            is SessionCommand.StartNextRound -> handleStartNextRound(command)
            is SessionCommand.SubmitAnswer -> handleSubmitAnswer(command)
            is SessionCommand.StartLlmForRound -> handleStartLlm(command)
            is SessionCommand.LlmFinalAnswerReceived -> handleLlmFinalAnswer(command)
            is SessionCommand.Timeout -> handleTimeout()
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
            )
            session = created

            gameEventBus.publish(GameEvent.SessionCreated(sessionId = sessionId, joinCode = joinCode))
            gameEventBus.publish(
                GameEvent.PlayerJoined(
                    sessionId = sessionId,
                    playerId = human.playerId,
                    nickname = human.nickname,
                )
            )
            gameEventBus.publish(
                GameEvent.PlayerJoined(
                    sessionId = sessionId,
                    playerId = llm.playerId,
                    nickname = llm.nickname,
                )
            )
            return
        }

        val existingSession = session ?: return
        if (existingSession.players.human.playerId != command.playerId) {
            gameEventBus.publish(
                GameEvent.SessionError(
                    sessionId = sessionId,
                    errorCode = "session_taken",
                    message = "session already has a different human player",
                )
            )
        }
    }

    private suspend fun handleStartNextRound(command: SessionCommand.StartNextRound) {
        val currentSession = session
            ?: return publishError("session_not_ready", "session not initialized")

        if (currentSession.players.human.playerId != command.playerId) {
            return publishError("forbidden", "player cannot start round")
        }

        when (val result = startRoundUseCase.execute(currentSession, opponentSpec)) {
            is StartRoundUseCase.Result.Success -> {
                session = result.session
                val round = result.round
                gameEventBus.publish(
                    GameEvent.RoundStarted(
                        sessionId = sessionId,
                        questionId = round.question.questionId,
                        roundId = round.roundId,
                        roundNumber = result.roundNumber,
                        questionPrompt = round.question.prompt,
                        choices = round.question.choices,
                        releasedAt = round.releasedAt,
                        handicapMs = round.handicap.toMillis(),
                        deadlineAt = round.deadline,
                        nonceToken = round.nonceToken,
                    )
                )
                scheduleLlmStart(round)
            }

            is StartRoundUseCase.Result.Failure -> {
                publishError(result.errorCode, result.message)
            }
        }
    }

    private suspend fun handleSubmitAnswer(command: SessionCommand.SubmitAnswer) {
        val currentSession = session
            ?: return publishError("session_not_ready", "session not initialized")

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
                session = result.session
                gameEventBus.publish(
                    GameEvent.SubmissionReceived(
                        sessionId = sessionId,
                        roundId = command.roundId,
                        playerType = result.playerType,
                    )
                )
                resolveRoundIfReady(command.roundId)
            }

            is SubmitAnswerUseCase.Result.Failure -> {
                publishError(result.errorCode, result.message)
            }
        }
    }

    private suspend fun handleStartLlm(command: SessionCommand.StartLlmForRound) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        if (!round.isInProgress) return

        if (llmJobs.containsKey(command.roundId)) return
        gameEventBus.publish(
            GameEvent.LlmThinking(
                sessionId = sessionId,
                roundId = command.roundId,
                progress = null,
            )
        )

        val streamState = roundStreamStates.computeIfAbsent(command.roundId) { RoundStreamState() }

        val job = scope.launch {
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

            streamState.getWithheldReasoning = orchestrationResult.getWithheldReasoning

            var seq = StreamSeq(0)

            orchestrationResult.events.collect { event ->
                when (event) {
                    is LlmStreamEvent.ReasoningDelta -> {
                        streamState.fullReasoningBuilder.append(event.deltaText)

                        gameEventBus.publish(
                            GameEvent.LlmReasoningDelta(
                                sessionId = sessionId,
                                roundId = round.roundId,
                                deltaText = event.deltaText,
                                seq = seq,
                            )
                        )
                        seq = seq.next()
                    }

                    is LlmStreamEvent.ReasoningTruncated -> {
                        gameEventBus.publish(
                            GameEvent.LlmReasoningTruncated(
                                sessionId = sessionId,
                                roundId = round.roundId,
                                droppedChars = event.droppedChars,
                            )
                        )
                    }

                    is LlmStreamEvent.ReasoningEnded -> {
                        gameEventBus.publish(
                            GameEvent.LlmReasoningEnded(
                                sessionId = sessionId,
                                roundId = round.roundId,
                            )
                        )
                    }

                    is LlmStreamEvent.FinalAnswer -> {
                        gameEventBus.publish(
                            GameEvent.LlmFinalAnswer(
                                sessionId = sessionId,
                                roundId = round.roundId,
                                answer = event.answer,
                            )
                        )
                        submit(
                            SessionCommand.LlmFinalAnswerReceived(
                                roundId = round.roundId,
                                answer = event.answer.finalAnswer,
                            )
                        )
                    }

                    is LlmStreamEvent.Error -> {
                        gameEventBus.publish(
                            GameEvent.LlmStreamError(
                                sessionId = sessionId,
                                roundId = round.roundId,
                                message = event.message,
                            )
                        )
                    }
                }
            }
        }

        job.invokeOnCompletion { llmJobs.remove(command.roundId) }
        llmJobs[command.roundId] = job
    }

    private suspend fun handleLlmFinalAnswer(command: SessionCommand.LlmFinalAnswerReceived) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == command.roundId } ?: return
        val llmPlayerId = currentSession.players.llm.playerId
        val result = submitAnswerUseCase.execute(
            session = currentSession,
            playerId = llmPlayerId,
            roundId = command.roundId,
            nonceToken = round.nonceToken,
            answer = command.answer,
            clientSentAt = null,
        )
        if (result is SubmitAnswerUseCase.Result.Success) {
            session = result.session

            // Check if human hasn't submitted yet - emit LlmAnswerLockIn idempotently
            val updatedRound = result.session.rounds.firstOrNull { it.roundId == command.roundId }
            val streamState = roundStreamStates[command.roundId]
            if (updatedRound?.humanSubmission == null && streamState?.lockInEmitted == false) {
                streamState.lockInEmitted = true
                gameEventBus.publish(
                    GameEvent.LlmAnswerLockIn(
                        sessionId = sessionId,
                        roundId = command.roundId,
                    )
                )
            }

            resolveRoundIfReady(command.roundId)
        }
    }

    private suspend fun resolveRoundIfReady(roundId: Uuid) {
        val currentSession = session ?: return
        val round = currentSession.rounds.firstOrNull { it.roundId == roundId } ?: return
        if (!round.hasAllSubmissions || round.result != null) return

        val humanSubmission = round.humanSubmission ?: return
        val llmSubmission = round.llmSubmission ?: return

        val humanOutcome = answerVerifier.verify(round.question, humanSubmission)
        val llmOutcome = answerVerifier.verify(round.question, llmSubmission)
        val correctAnswer = correctAnswerFor(round.question)
        val result = ScorePolicy.compute(
            round = round,
            correctAnswer = correctAnswer,
            humanCorrect = humanOutcome.correct,
            llmCorrect = llmOutcome.correct,
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

        gameEventBus.publish(
            GameEvent.RoundResolved(
                sessionId = sessionId,
                roundId = roundId,
                correctAnswer = result.correctAnswer,
                humanCorrect = humanOutcome.correct,
                llmCorrect = llmOutcome.correct,
                humanScore = result.humanOutcome.score.points,
                llmScore = result.llmOutcome.score.points,
                winner = result.winner.name,
            )
        )

        // Emit full reasoning reveal at round completion
        val streamState = roundStreamStates.remove(roundId)
        if (streamState != null) {
            // Full reasoning = emitted reasoning + withheld reasoning
            val fullReasoning = buildString {
                append(streamState.fullReasoningBuilder.toString())
                // Add withheld reasoning from orchestrator if available
                streamState.getWithheldReasoning?.invoke()?.let { withheld ->
                    if (withheld.isNotEmpty()) append(withheld)
                }
            }
            if (fullReasoning.isNotEmpty()) {
                gameEventBus.publish(
                    GameEvent.LlmReasoningReveal(
                        sessionId = sessionId,
                        roundId = roundId,
                        fullReasoning = fullReasoning,
                    )
                )
            }
        }

        if (updatedSession.isCompleted) {
            val (humanScore, llmScore) = updatedSession.calculateTotalScores()
            gameEventBus.publish(
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
                gameEventBus.publish(
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
        val result = SessionResult(
            sessionId = session.sessionId,
            gameMode = session.mode,
            difficulty = round.question.difficulty,
            llmProfileName = session.llmProfile.displayName,
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
        gameEventBus.publish(
            GameEvent.SessionError(
                sessionId = sessionId,
                errorCode = errorCode,
                message = message,
            )
        )
    }

    private fun scheduleLlmStart(round: Round) {
        val delayMs = round.handicap.toMillis().coerceAtLeast(0L)
        scope.launch {
            delay(delayMs)
            submit(SessionCommand.StartLlmForRound(roundId = round.roundId))
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

    private suspend fun handleTimeout() {
        val currentSession = session ?: return
        if (currentSession.isCompleted) return

        session = currentSession.copy(state = SessionState.CANCELLED)
        gameEventBus.publish(
            GameEvent.SessionTerminated(
                sessionId = sessionId,
                reason = "timeout",
            )
        )
        onTerminate(sessionId)
    }
}

internal sealed interface SessionCommand {
    data class JoinSession(
        val sessionId: Uuid,
        val playerId: Uuid,
        val nickname: String,
    ) : SessionCommand

    data class StartNextRound(
        val sessionId: Uuid,
        val playerId: Uuid,
    ) : SessionCommand

    data class SubmitAnswer(
        val sessionId: Uuid,
        val playerId: Uuid,
        val roundId: Uuid,
        val nonceToken: String,
        val answer: Answer,
        val clientSentAt: Instant?,
    ) : SessionCommand

    data class StartLlmForRound(
        val roundId: Uuid,
    ) : SessionCommand

    data class LlmFinalAnswerReceived(
        val roundId: Uuid,
        val answer: Answer,
    ) : SessionCommand

    data object Timeout : SessionCommand
}
