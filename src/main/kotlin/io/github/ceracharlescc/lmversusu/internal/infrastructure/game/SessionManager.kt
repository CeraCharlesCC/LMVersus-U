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

        internal const val JOIN_SESSION_TIMEOUT_MS = 5000L
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

    private val actors = ConcurrentHashMap<Uuid, SessionEntry>()
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val idleTimeoutJobs = ConcurrentHashMap<Uuid, Job>()

    private val maxLifespanJobs = ConcurrentHashMap<Uuid, Job>()
    private val sessionLimitRegistry = SessionLimitRegistry(clock)
    private val activeSessionLimiter = ActiveSessionLimiter { mode ->
        limitContextFor(mode).limitConfig.maxActiveSessions
    }

    suspend fun joinSession(
        sessionId: Uuid,
        clientIdentity: ClientIdentity,
        nickname: String,
        opponentSpecId: String,
    ): JoinResult {
        val opponentSpec = opponentSpecRepository.findById(opponentSpecId)
            ?: return JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "opponent_spec_not_found",
                message = "opponent spec not found",
            )

        // Fast-path: session already exists.
        when (val existing = actors[sessionId]) {
            is SessionEntry.Active -> {
                return joinExistingSession(
                    entry = existing,
                    sessionId = sessionId,
                    clientIdentity = clientIdentity,
                    nickname = nickname,
                    opponentSpecId = opponentSpecId,
                    isNewSession = false,
                )
            }

            is SessionEntry.Creating -> {
                return awaitCreationAndJoin(
                    entry = existing,
                    sessionId = sessionId,
                    clientIdentity = clientIdentity,
                    nickname = nickname,
                    opponentSpecId = opponentSpecId,
                )
            }

            null -> Unit
        }

        val mode = opponentSpec.mode
        val limitContext = limitContextFor(mode)

        val creatingEntry = SessionEntry.Creating(CompletableDeferred())
        val racedEntry = actors.putIfAbsent(sessionId, creatingEntry)
        if (racedEntry != null) {
            return when (racedEntry) {
                is SessionEntry.Active -> joinExistingSession(
                    entry = racedEntry,
                    sessionId = sessionId,
                    clientIdentity = clientIdentity,
                    nickname = nickname,
                    opponentSpecId = opponentSpecId,
                    isNewSession = false,
                )

                is SessionEntry.Creating -> awaitCreationAndJoin(
                    entry = racedEntry,
                    sessionId = sessionId,
                    clientIdentity = clientIdentity,
                    nickname = nickname,
                    opponentSpecId = opponentSpecId,
                )
            }
        }

        var transferred = false
        var permit: ActiveSessionLimiter.Permit? = null
        var newActor: SessionActor? = null
        try {
            permit = activeSessionLimiter.tryAcquire(mode)
            if (permit == null) {
                completeCreationFailure(
                    entry = creatingEntry,
                    sessionId = sessionId,
                    errorCode = "session_limit_exceeded",
                    message = "too many active ${limitContext.modeLabel} sessions",
                )
                return JoinResult.Failure(
                    sessionId = sessionId,
                    errorCode = "session_limit_exceeded",
                    message = "too many active ${limitContext.modeLabel} sessions",
                )
            }

            val limitFailure = checkSessionCreationLimits(clientIdentity, mode)
            if (limitFailure != null) {
                completeCreationFailure(
                    entry = creatingEntry,
                    sessionId = sessionId,
                    errorCode = limitFailure.errorCode,
                    message = limitFailure.message,
                )
                return JoinResult.Failure(
                    sessionId = sessionId,
                    errorCode = limitFailure.errorCode,
                    message = limitFailure.message,
                )
            }

            val mailboxCapacity = appConfig.sessionLimitConfig.actorMailboxCapacity.coerceAtLeast(1)
            newActor = SessionActor(
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

            val newEntry = SessionEntry.Active(actor = newActor, mode = mode, permit = permit)
            if (!actors.replace(sessionId, creatingEntry, newEntry)) {
                val currentEntry = actors[sessionId]
                if (currentEntry is SessionEntry.Active) {
                    creatingEntry.deferred.complete(CreationOutcome.Created(currentEntry))
                    newActor.shutdown()
                    return joinExistingSession(
                        entry = currentEntry,
                        sessionId = sessionId,
                        clientIdentity = clientIdentity,
                        nickname = nickname,
                        opponentSpecId = opponentSpecId,
                        isNewSession = false,
                    )
                }

                completeCreationFailure(
                    entry = creatingEntry,
                    sessionId = sessionId,
                    errorCode = "session_busy",
                    message = "session is busy, please retry",
                )
                return JoinResult.Failure(
                    sessionId = sessionId,
                    errorCode = "session_busy",
                    message = "session is busy, please retry",
                )
            }

            // Permit is now owned by the entry and will be released from removeSession.
            transferred = true
            creatingEntry.deferred.complete(CreationOutcome.Created(newEntry))

            // joinExistingSession is suspendable and could throw; if it does, we must remove the entry and
            // release the permit to avoid leaking capacity.
            return try {
                val result = joinExistingSession(
                    entry = newEntry,
                    sessionId = sessionId,
                    clientIdentity = clientIdentity,
                    nickname = nickname,
                    opponentSpecId = opponentSpecId,
                    isNewSession = true,
                )

                // If the very first join attempt fails, tear down the just-created session so it
                // doesn't consume an active-session slot.
                if (result is JoinResult.Failure) {
                    removeSession(sessionId)
                }

                result
            } catch (t: Throwable) {
                // Best-effort cleanup. removeSession will shutdown actor, revoke bus, and close permit.
                removeSession(sessionId)
                throw t
            }
        } finally {
            if (!transferred) {
                newActor?.shutdown()
                permit?.close()
            }
        }
    }



    private suspend fun awaitCreationAndJoin(
        entry: SessionEntry.Creating,
        sessionId: Uuid,
        clientIdentity: ClientIdentity,
        nickname: String,
        opponentSpecId: String,
    ): JoinResult {
        return when (val outcome = withTimeoutOrNull(JOIN_SESSION_TIMEOUT_MS) { entry.deferred.await() }) {
            is CreationOutcome.Created -> joinExistingSession(
                entry = outcome.entry,
                sessionId = sessionId,
                clientIdentity = clientIdentity,
                nickname = nickname,
                opponentSpecId = opponentSpecId,
                isNewSession = false,
            )

            is CreationOutcome.Failed -> JoinResult.Failure(
                sessionId = sessionId,
                errorCode = outcome.errorCode,
                message = outcome.message,
            )

            null -> JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "join_timeout",
                message = "session join timed out",
            )
        }
    }

    private suspend fun joinExistingSession(
        entry: SessionEntry.Active,
        sessionId: Uuid,
        clientIdentity: ClientIdentity,
        nickname: String,
        opponentSpecId: String,
        isNewSession: Boolean,
    ): JoinResult {
        val actor = entry.actor
        if (actor.opponentSpecId != opponentSpecId) {
            return JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "opponent_mismatch",
                message = "session already uses a different opponent",
            )
        }

        val response = CompletableDeferred<JoinResponse>()
        val accepted = actor.submit(
            SessionCommand.JoinSession(
                sessionId = sessionId,
                playerId = clientIdentity.playerId,
                nickname = nickname,
                response = response,
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

        return when (val joinResponse = withTimeoutOrNull(JOIN_SESSION_TIMEOUT_MS) { response.await() }) {
            is JoinResponse.Accepted -> {
                if (isNewSession) {
                    scheduleMaxLifespan(sessionId)
                }
                scheduleIdleTimeout(sessionId)

                JoinResult.Success(
                    sessionId = sessionId,
                    playerId = clientIdentity.playerId,
                    opponentSpecId = opponentSpecId,
                    nickname = nickname,
                )
            }

            is JoinResponse.Rejected -> {
                if (isNewSession) {
                    removeSession(sessionId)
                }
                JoinResult.Failure(
                    sessionId = sessionId,
                    errorCode = joinResponse.errorCode,
                    message = joinResponse.message,
                )
            }

            null -> {
                if (isNewSession) {
                    removeSession(sessionId)
                }
                JoinResult.Failure(
                    sessionId = sessionId,
                    errorCode = "join_timeout",
                    message = "session join timed out",
                )
            }
        }
    }

    suspend fun startNextRound(sessionId: Uuid, playerId: Uuid): CommandResult {
        val actor = (actors[sessionId] as? SessionEntry.Active)?.actor
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
        val actor = (actors[sessionId] as? SessionEntry.Active)?.actor
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
        if (actors[sessionId] !is SessionEntry.Active) {
            return TouchResult.SessionNotFound
        }
        scheduleIdleTimeout(sessionId)
        return TouchResult.Success
    }

    private fun scheduleIdleTimeout(sessionId: Uuid) {
        idleTimeoutJobs[sessionId]?.cancel()
        idleTimeoutJobs[sessionId] = supervisorScope.launch {
            delay(IDLE_TIMEOUT_MS)
            (actors[sessionId] as? SessionEntry.Active)?.actor?.submit(SessionCommand.Timeout(reason = "timeout"))
        }
    }

    private fun scheduleMaxLifespan(sessionId: Uuid) {
        maxLifespanJobs[sessionId]?.cancel()
        maxLifespanJobs[sessionId] = supervisorScope.launch {
            delay(MAX_LIFESPAN_MS)
            // Route through actor to ensure SessionResolved is emitted
            (actors[sessionId] as? SessionEntry.Active)?.actor?.submitCritical(SessionCommand.Timeout(reason = "max_lifespan"))
        }
    }

    private fun removeSession(sessionId: Uuid) {
        idleTimeoutJobs.remove(sessionId)?.cancel()
        maxLifespanJobs.remove(sessionId)?.cancel()
        val entry = actors.remove(sessionId)
        when (entry) {
            is SessionEntry.Active -> {
                entry.actor.shutdown()
                entry.permit.close()
            }

            is SessionEntry.Creating -> {
                entry.deferred.complete(
                    CreationOutcome.Failed(
                        errorCode = "session_creation_cancelled",
                        message = "session creation cancelled",
                    )
                )
            }

            null -> Unit
        }
        gameEventBus.revokeSession(sessionId)
    }

    fun shutdownAll() {
        supervisorScope.cancel()

        val sessionIds = actors.keys.toList()
        sessionIds.forEach { removeSession(it) }

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

    private sealed interface SessionEntry {
        data class Creating(val deferred: CompletableDeferred<CreationOutcome>) : SessionEntry
        data class Active(
            val actor: SessionActor,
            val mode: GameMode,
            val permit: ActiveSessionLimiter.Permit,
        ) : SessionEntry
    }

    private sealed interface CreationOutcome {
        data class Created(val entry: SessionEntry.Active) : CreationOutcome
        data class Failed(val errorCode: String, val message: String) : CreationOutcome
    }

    private fun completeCreationFailure(
        entry: SessionEntry.Creating,
        sessionId: Uuid,
        errorCode: String,
        message: String,
    ) {
        entry.deferred.complete(CreationOutcome.Failed(errorCode = errorCode, message = message))
        actors.remove(sessionId, entry)
    }

    private fun limitContextFor(mode: GameMode): LimitContext =
        when (mode) {
            GameMode.PREMIUM -> LimitContext(
                limitConfig = appConfig.sessionLimitConfig.premium,
                modeLabel = "premium",
            )

            GameMode.LIGHTWEIGHT -> LimitContext(
                limitConfig = appConfig.sessionLimitConfig.lightweight,
                modeLabel = "lightweight",
            )
        }

    private fun checkSessionCreationLimits(
        clientIdentity: ClientIdentity,
        mode: GameMode,
    ): LimitFailure? {
        val limitContext = limitContextFor(mode)

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
}
