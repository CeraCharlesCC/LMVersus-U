@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.application.port.AnswerVerifier
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.LlmPlayerGateway
import io.github.ceracharlescc.lmversusu.internal.application.service.LlmStreamOrchestrator
import io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase
import io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.ClientIdentity
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
    private val appConfig: AppConfig,
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
    private val sessionLimitRegistry = SessionLimitRegistry(clock)

    suspend fun joinSession(
        sessionId: Uuid,
        clientIdentity: ClientIdentity,
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
        if (isNewSession) {
            val limitFailure = checkSessionCreationLimits(clientIdentity, opponentSpec.mode)
            if (limitFailure != null) {
                return JoinResult.Failure(
                    sessionId = sessionId,
                    errorCode = limitFailure.errorCode,
                    message = limitFailure.message,
                )
            }
        }

        val actor = actors.computeIfAbsent(sessionId) {
            val mailboxCapacity = appConfig.sessionLimitConfig.actorMailboxCapacity.coerceAtLeast(1)
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
                mailboxCapacity = mailboxCapacity,
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

        val accepted = actor.submit(
            SessionCommand.JoinSession(
                sessionId = sessionId,
                playerId = clientIdentity.playerId,
                nickname = nickname,
            )
        )
        if (!accepted) {
            if (isNewSession) {
                removeSession(sessionId)
            }
            return JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "session_busy",
                message = "session is busy, please retry",
            )
        }

        if (isNewSession) {
            scheduleMaxLifespan(sessionId)
        }
        scheduleIdleTimeout(sessionId)

        return JoinResult.Success(
            sessionId = sessionId,
            playerId = clientIdentity.playerId,
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
        if (!actor.submit(SessionCommand.StartNextRound(sessionId = sessionId, playerId = playerId))) {
            return CommandResult.Failure(
                sessionId = sessionId,
                errorCode = "session_busy",
                message = "session is busy, please retry",
            )
        }
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
        if (
            !actor.submit(
                SessionCommand.SubmitAnswer(
                    sessionId = sessionId,
                    playerId = playerId,
                    roundId = roundId,
                    nonceToken = nonceToken,
                    answer = answer,
                    clientSentAt = clientSentAt,
                )
            )
        ) {
            return CommandResult.Failure(
                sessionId = sessionId,
                errorCode = "session_busy",
                message = "session is busy, please retry",
            )
        }
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
            // Route through actor to ensure SessionResolved is emitted
            actors[sessionId]?.submit(SessionCommand.Timeout(reason = "max_lifespan"))
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

    private data class LimitContext(
        val limitConfig: AppConfig.ModeLimitConfig,
        val modeLabel: String,
    )

    private data class LimitFailure(
        val errorCode: String,
        val message: String,
    )

    private fun checkSessionCreationLimits(
        clientIdentity: ClientIdentity,
        mode: GameMode,
    ): LimitFailure? {
        val limitContext = when (mode) {
            GameMode.PREMIUM -> LimitContext(
                limitConfig = appConfig.sessionLimitConfig.premium,
                modeLabel = "premium",
            )

            GameMode.LIGHTWEIGHT -> LimitContext(
                limitConfig = appConfig.sessionLimitConfig.lightweight,
                modeLabel = "lightweight",
            )
        }

        val activeSessions = countActiveSessions(mode)
        val maxActive = limitContext.limitConfig.maxActiveSessions
        if (maxActive > 0 && activeSessions >= maxActive) {
            return LimitFailure(
                errorCode = "session_limit_exceeded",
                message = "too many active ${limitContext.modeLabel} sessions",
            )
        }

        val ipAddress = clientIdentity.ipAddress.ifBlank { "unknown" }
        val playerKey = clientIdentity.playerId.toString()
        val modePrefix = "mode:${limitContext.modeLabel}"
        val dailyWindowMillis = appConfig.sessionLimitConfig.dailyWindowMillis

        val entries = buildList {
            val perPersonDailyLimit = limitContext.limitConfig.perPersonDailyLimit
            if (perPersonDailyLimit > 0) {
                add(
                    SessionLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:ip:$ipAddress:daily",
                        windowMillis = dailyWindowMillis,
                        limit = perPersonDailyLimit,
                        failure = SessionLimitRegistry.LimitFailure(
                            errorCode = "session_limit_exceeded",
                            message = "daily ${limitContext.modeLabel} session limit reached",
                        ),
                    )
                )
                add(
                    SessionLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:player:$playerKey:daily",
                        windowMillis = dailyWindowMillis,
                        limit = perPersonDailyLimit,
                        failure = SessionLimitRegistry.LimitFailure(
                            errorCode = "session_limit_exceeded",
                            message = "daily ${limitContext.modeLabel} session limit reached",
                        ),
                    )
                )
            }

            val perPersonWindowLimit = limitContext.limitConfig.perPersonWindowLimit
            val perPersonWindowMillis = limitContext.limitConfig.perPersonWindowMillis
            if (perPersonWindowLimit > 0 && perPersonWindowMillis > 0) {
                add(
                    SessionLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:ip:$ipAddress:window",
                        windowMillis = perPersonWindowMillis,
                        limit = perPersonWindowLimit,
                        failure = SessionLimitRegistry.LimitFailure(
                            errorCode = "rate_limited",
                            message = "session creation rate limited",
                        ),
                    )
                )
                add(
                    SessionLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:player:$playerKey:window",
                        windowMillis = perPersonWindowMillis,
                        limit = perPersonWindowLimit,
                        failure = SessionLimitRegistry.LimitFailure(
                            errorCode = "rate_limited",
                            message = "session creation rate limited",
                        ),
                    )
                )
            }

            val globalWindowLimit = limitContext.limitConfig.globalWindowLimit
            val globalWindowMillis = limitContext.limitConfig.globalWindowMillis
            if (globalWindowLimit > 0 && globalWindowMillis > 0) {
                add(
                    SessionLimitRegistry.LimitEntry(
                        key = "$modePrefix:global:window",
                        windowMillis = globalWindowMillis,
                        limit = globalWindowLimit,
                        failure = SessionLimitRegistry.LimitFailure(
                            errorCode = "rate_limited",
                            message = "global ${limitContext.modeLabel} session rate limit reached",
                        ),
                    )
                )
            }

            val globalDailyLimit = limitContext.limitConfig.globalDailyLimit
            if (globalDailyLimit > 0) {
                add(
                    SessionLimitRegistry.LimitEntry(
                        key = "$modePrefix:global:daily",
                        windowMillis = dailyWindowMillis,
                        limit = globalDailyLimit,
                        failure = SessionLimitRegistry.LimitFailure(
                            errorCode = "session_limit_exceeded",
                            message = "global daily ${limitContext.modeLabel} session limit reached",
                        ),
                    )
                )
            }
        }

        val failure = sessionLimitRegistry.tryAcquire(entries)
        if (failure != null) {
            return LimitFailure(errorCode = failure.errorCode, message = failure.message)
        }

        return null
    }

    private fun countActiveSessions(mode: GameMode): Int {
        return actors.values.count { actor -> actor.mode == mode }
    }
}
