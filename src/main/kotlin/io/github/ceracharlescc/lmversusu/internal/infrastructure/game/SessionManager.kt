@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.application.port.AnswerVerifier
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.LlmPlayerGateway
import io.github.ceracharlescc.lmversusu.internal.application.service.LlmStreamOrchestrator
import io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase
import io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class SessionManager @Inject constructor(
    private val logger: Logger,
    private val opponentSpecRepository: OpponentSpecRepository,
    private val gameEventBus: GameEventBus,
    private val startRoundUseCase: StartRoundUseCase,
    private val submitAnswerUseCase: SubmitAnswerUseCase,
    private val answerVerifier: AnswerVerifier,
    private val llmPlayerGateway: LlmPlayerGateway,
    private val llmStreamOrchestrator: LlmStreamOrchestrator,
    private val resultsRepository: ResultsRepository,
    private val clock: Clock,
) {
    companion object {
        /** Session idle timeout - terminate abandoned sessions after no activity (10 minutes) */
        internal const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        /** Maximum session lifespan - absolute limit regardless of activity (60 minutes) */
        internal const val MAX_LIFESPAN_MS = 60 * 60 * 1000L

        /** Grace period after round deadline before idle timeout applies (60 seconds) */
        internal const val ROUND_GRACE_MS = 60_000L
    }

    sealed interface JoinResult {
        data class Success(
            val sessionId: Uuid,
            val playerId: Uuid,
            val opponentSpecId: String,
            val nickname: String,
        ) : JoinResult

        data class Failure(
            val sessionId: Uuid? = null,
            val errorCode: String,
            val message: String,
        ) : JoinResult
    }

    sealed interface CommandResult {
        data class Success(val sessionId: Uuid) : CommandResult
        data class Failure(
            val sessionId: Uuid? = null,
            val errorCode: String,
            val message: String,
        ) : CommandResult
    }

    sealed interface TouchResult {
        data object Success : TouchResult
        data object SessionNotFound : TouchResult
    }

    private val actors = ConcurrentHashMap<Uuid, SessionActor>()
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val idleTimeoutJobs = ConcurrentHashMap<Uuid, Job>()

    private val maxLifespanJobs = ConcurrentHashMap<Uuid, Job>()

    suspend fun joinSession(
        sessionId: Uuid,
        playerId: Uuid,
        nickname: String,
        opponentSpecId: String,
    ): JoinResult {
        val opponentSpec = opponentSpecRepository.findById(opponentSpecId)
            ?: return JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "opponent_not_found",
                message = "opponent spec not found",
            )

        val isNewSession = !actors.containsKey(sessionId)

        val actor = actors.computeIfAbsent(sessionId) {
            SessionActor(
                logger = logger,
                sessionId = sessionId,
                opponentSpec = opponentSpec,
                gameEventBus = gameEventBus,
                startRoundUseCase = startRoundUseCase,
                submitAnswerUseCase = submitAnswerUseCase,
                answerVerifier = answerVerifier,
                llmPlayerGateway = llmPlayerGateway,
                llmStreamOrchestrator = llmStreamOrchestrator,
                resultsRepository = resultsRepository,
                clock = clock,
                onTerminate = { id -> removeSession(id) },
            )
        }

        if (actor.opponentSpecId != opponentSpecId) {
            return JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "opponent_mismatch",
                message = "session already uses a different opponent",
            )
        }

        actor.submit(
            SessionCommand.JoinSession(
                sessionId = sessionId,
                playerId = playerId,
                nickname = nickname,
            )
        )

        if (isNewSession) {
            scheduleMaxLifespan(sessionId)
        }
        scheduleIdleTimeout(sessionId)

        return JoinResult.Success(
            sessionId = sessionId,
            playerId = playerId,
            opponentSpecId = opponentSpecId,
            nickname = nickname,
        )
    }

    suspend fun startNextRound(sessionId: Uuid, playerId: Uuid): CommandResult {
        val actor = actors[sessionId]
            ?: return CommandResult.Failure(
                sessionId = sessionId,
                errorCode = "session_not_found",
                message = "session not found",
            )

        scheduleIdleTimeout(sessionId)
        actor.submit(SessionCommand.StartNextRound(sessionId = sessionId, playerId = playerId))
        return CommandResult.Success(sessionId)
    }

    suspend fun submitAnswer(
        sessionId: Uuid,
        playerId: Uuid,
        roundId: Uuid,
        nonceToken: String,
        answer: io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer,
        clientSentAt: Instant?,
    ): CommandResult {
        val actor = actors[sessionId]
            ?: return CommandResult.Failure(
                sessionId = sessionId,
                errorCode = "session_not_found",
                message = "session not found",
            )

        scheduleIdleTimeout(sessionId)
        actor.submit(
            SessionCommand.SubmitAnswer(
                sessionId = sessionId,
                playerId = playerId,
                roundId = roundId,
                nonceToken = nonceToken,
                answer = answer,
                clientSentAt = clientSentAt,
            )
        )
        return CommandResult.Success(sessionId)
    }

    suspend fun touchSession(sessionId: Uuid): TouchResult {
        if (!actors.containsKey(sessionId)) {
            return TouchResult.SessionNotFound
        }
        scheduleIdleTimeout(sessionId)
        return TouchResult.Success
    }

    private fun scheduleIdleTimeout(sessionId: Uuid) {
        idleTimeoutJobs[sessionId]?.cancel()
        idleTimeoutJobs[sessionId] = supervisorScope.launch {
            delay(IDLE_TIMEOUT_MS)
            actors[sessionId]?.submit(SessionCommand.Timeout(reason = "timeout"))
        }
    }

    private fun scheduleMaxLifespan(sessionId: Uuid) {
        maxLifespanJobs[sessionId] = supervisorScope.launch {
            delay(MAX_LIFESPAN_MS)
            // Only terminate if session still exists
            val actor = actors[sessionId]
            if (actor != null) {
                gameEventBus.publish(
                    GameEvent.SessionTerminated(
                        sessionId = sessionId,
                        reason = "max_lifespan",
                    )
                )
                removeSession(sessionId)
            }
        }
    }

    private fun removeSession(sessionId: Uuid) {
        idleTimeoutJobs.remove(sessionId)?.cancel()
        maxLifespanJobs.remove(sessionId)?.cancel()
        actors.remove(sessionId)?.shutdown()
    }

    fun shutdownAll() {
        supervisorScope.cancel()
        actors.forEach { (_, actor) -> actor.shutdown() }
        actors.clear()
        idleTimeoutJobs.clear()
        maxLifespanJobs.clear()
    }
}
