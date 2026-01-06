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
    private val playerActiveSessionIndex: PlayerActiveSessionIndex,
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


    private val actors = ConcurrentHashMap<Uuid, SessionEntry>()
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val idleTimeoutJobs = ConcurrentHashMap<Uuid, Job>()

    private val maxLifespanJobs = ConcurrentHashMap<Uuid, Job>()
    private val sessionLimitRegistry = SessionLimitRegistry(clock)
    private val activeSessionLimiter = ActiveSessionLimiter { mode ->
        limitContextFor(mode).limitConfig.maxActiveSessions
    }

    suspend fun joinSession(
        sessionId: Uuid?,
        clientIdentity: ClientIdentity,
        nickname: String,
        opponentSpecId: String,
    ): JoinResult {
        val playerId = clientIdentity.playerId

        suspend fun rejectActiveSessionExists(existingSessionId: Uuid, requestedSessionId: Uuid?): JoinResult.Failure {
            logger.debug(
                "Rejecting join for player {} session {} because active session {} exists",
                playerId,
                requestedSessionId,
                existingSessionId
            )
            return JoinResult.Failure(
                sessionId = existingSessionId,
                errorCode = "active_session_exists",
                message = "player already has an active session",
            )
        }

        suspend fun joinFromEntry(
            entry: SessionEntry,
            sid: Uuid,
            oppSpecId: String,
            isNewSession: Boolean,
        ): JoinResult = when (entry) {
            is SessionEntry.Active -> joinExistingSession(
                entry = entry,
                sessionId = sid,
                clientIdentity = clientIdentity,
                nickname = nickname,
                opponentSpecId = oppSpecId,
                isNewSession = isNewSession,
            )

            is SessionEntry.Creating -> awaitCreationAndJoin(
                entry = entry,
                sessionId = sid,
                clientIdentity = clientIdentity,
                nickname = nickname,
                opponentSpecId = oppSpecId,
            )
        }

        var binding = playerActiveSessionIndex.get(playerId)
        if (binding != null) {
            val entry = entryForBinding(binding, playerId)

            if (entry != null) {
                if (sessionId == null || sessionId == binding.sessionId) {
                    logger.debug("Resuming active session {} for player {}", binding.sessionId, playerId)
                    return joinFromEntry(
                        entry = entry,
                        sid = binding.sessionId,
                        oppSpecId = binding.opponentSpecId,
                        isNewSession = false,
                    )
                }
                return rejectActiveSessionExists(binding.sessionId, sessionId)
            }

            // Binding exists but we can't resolve its entry; reconcile stale/mismatched bindings.
            val activeEntry = actors[binding.sessionId] as? SessionEntry.Active
            if (activeEntry != null && activeEntry.ownerPlayerId != playerId) {
                playerActiveSessionIndex.clear(playerId, binding.sessionId)
                logger.debug(
                    "Cleared active session binding for player {} due to owner mismatch on session {}",
                    playerId,
                    binding.sessionId
                )
                binding = null
            } else if (sessionId != null && sessionId != binding.sessionId) {
                return rejectActiveSessionExists(binding.sessionId, sessionId)
            }
        }


        val failureSessionId = when {
            sessionId != null -> sessionId
            binding != null -> binding.sessionId
            else -> null
        }

        // SECURITY: Pre-reservation ownership check
        if (sessionId != null) {
            when (val existingEntry = actors[sessionId]) {
                is SessionEntry.Active -> {
                    if (existingEntry.ownerPlayerId != playerId) {
                        logger.debug(
                            "Rejecting join for player {} - session {} owned by different player",
                            playerId,
                            sessionId
                        )
                        return JoinResult.Failure(
                            sessionId = sessionId,
                            errorCode = "session_not_owned",
                            message = "session is owned by another player",
                        )
                    }
                }

                is SessionEntry.Creating -> {
                    // Don't allow joining a Creating session by explicit ID unless it matches player's existing binding
                    val existingBinding = playerActiveSessionIndex.get(playerId)
                    if (existingBinding?.sessionId != sessionId) {
                        logger.debug(
                            "Rejecting join for player {} - session {} is being created by another player",
                            playerId,
                            sessionId
                        )
                        return JoinResult.Failure(
                            sessionId = sessionId,
                            errorCode = "session_creating",
                            message = "session is being created by another player",
                        )
                    }
                }

                null -> Unit
            }
        }

        var chosenSessionId = sessionId ?: Uuid.random()
        var chosenOpponentSpecId = opponentSpecId
        var shouldReserve = true

        if (binding != null && (sessionId == null || sessionId == binding.sessionId)) {
            chosenSessionId = binding.sessionId
            chosenOpponentSpecId = binding.opponentSpecId
            shouldReserve = false
            logger.debug("Continuing reserved session {} for player {}", binding.sessionId, playerId)
        }

        // Validate opponent spec BEFORE creating any binding to prevent poisoned bindings
        val opponentSpec = opponentSpecRepository.findById(chosenOpponentSpecId)
            ?: return JoinResult.Failure(
                sessionId = failureSessionId,
                errorCode = "opponent_spec_not_found",
                message = "opponent spec not found",
            )

        if (shouldReserve) {
            val newBinding = PlayerActiveSessionIndex.Binding(
                sessionId = chosenSessionId,
                opponentSpecId = chosenOpponentSpecId,
                createdAt = clock.instant(),
            )
            val reserved = playerActiveSessionIndex.getOrReserve(playerId, newBinding)

            if (reserved.sessionId == chosenSessionId) {
                logger.debug("Reserved session {} for player {}", chosenSessionId, playerId)
            } else {
                logger.debug(
                    "Player {} already has reserved session {}, joining instead of {}",
                    playerId,
                    reserved.sessionId,
                    chosenSessionId
                )
            }

            if (reserved.sessionId != chosenSessionId) {
                if (sessionId != null && sessionId != reserved.sessionId) {
                    return rejectActiveSessionExists(reserved.sessionId, sessionId)
                }

                val existingEntry = entryForBinding(reserved, playerId)
                if (existingEntry != null) {
                    logger.debug(
                        "Player {} already reserved session {}, joining instead of {}",
                        playerId,
                        reserved.sessionId,
                        chosenSessionId
                    )
                    return joinFromEntry(
                        entry = existingEntry,
                        sid = reserved.sessionId,
                        oppSpecId = reserved.opponentSpecId,
                        isNewSession = false,
                    )
                }

                chosenSessionId = reserved.sessionId
                chosenOpponentSpecId = reserved.opponentSpecId
            }
        }

        // Fast-path: session already exists.
        actors[chosenSessionId]?.let { existing ->
            return joinFromEntry(
                entry = existing,
                sid = chosenSessionId,
                oppSpecId = chosenOpponentSpecId,
                isNewSession = false,
            )
        }

        val mode = opponentSpec.mode
        val limitContext = limitContextFor(mode)

        val creatingEntry = SessionEntry.Creating(CompletableDeferred())
        val racedEntry = actors.putIfAbsent(chosenSessionId, creatingEntry)
        if (racedEntry != null) {
            return joinFromEntry(
                entry = racedEntry,
                sid = chosenSessionId,
                oppSpecId = chosenOpponentSpecId,
                isNewSession = false,
            )
        }

        var transferred = false
        var permit: ActiveSessionLimiter.Permit? = null
        var newActor: SessionActor? = null

        fun creationFailure(errorCode: String, message: String): JoinResult.Failure {
            completeCreationFailure(
                entry = creatingEntry,
                sessionId = chosenSessionId,
                errorCode = errorCode,
                message = message,
            )
            playerActiveSessionIndex.clear(playerId, chosenSessionId)
            return JoinResult.Failure(
                sessionId = chosenSessionId,
                errorCode = errorCode,
                message = message,
            )
        }

        try {
            permit = activeSessionLimiter.tryAcquire(mode)
                ?: return creationFailure(
                    errorCode = "session_limit_exceeded",
                    message = "too many active ${limitContext.modeLabel} sessions",
                )

            val limitFailure = checkSessionCreationLimits(clientIdentity, mode)
            if (limitFailure != null) {
                return creationFailure(
                    errorCode = limitFailure.errorCode,
                    message = limitFailure.message,
                )
            }

            val mailboxCapacity = appConfig.sessionLimitConfig.actorMailboxCapacity.coerceAtLeast(1)
            newActor = SessionActor(
                logger = logger,
                sessionId = chosenSessionId,
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

            // First join while still "Creating"
            val newEntry = SessionEntry.Active(
                sessionId = chosenSessionId,
                actor = newActor,
                mode = mode,
                permit = permit,
                ownerPlayerId = playerId,
            )
            val firstJoinResult = joinExistingSession(
                entry = newEntry,
                sessionId = chosenSessionId,
                clientIdentity = clientIdentity,
                nickname = nickname,
                opponentSpecId = chosenOpponentSpecId,
                isNewSession = true,
            )

            if (firstJoinResult is JoinResult.Failure) {
                completeCreationFailure(
                    entry = creatingEntry,
                    sessionId = chosenSessionId,
                    errorCode = firstJoinResult.errorCode,
                    message = firstJoinResult.message,
                )
                playerActiveSessionIndex.clear(playerId, chosenSessionId)
                removeSession(chosenSessionId)
                return firstJoinResult
            }

            // Publish as Active
            if (!actors.replace(chosenSessionId, creatingEntry, newEntry)) {
                completeCreationFailure(
                    entry = creatingEntry,
                    sessionId = chosenSessionId,
                    errorCode = "session_creation_cancelled",
                    message = "session creation cancelled",
                )
                playerActiveSessionIndex.clear(playerId, chosenSessionId)
                removeSession(chosenSessionId)
                return JoinResult.Failure(
                    sessionId = chosenSessionId,
                    errorCode = "session_creation_cancelled",
                    message = "session creation cancelled",
                )
            }

            transferred = true
            creatingEntry.deferred.complete(CreationOutcome.Created(newEntry))
            return firstJoinResult
        } catch (t: Throwable) {
            completeCreationFailure(
                entry = creatingEntry,
                sessionId = chosenSessionId,
                errorCode = "session_creation_failed",
                message = "session creation failed",
            )
            playerActiveSessionIndex.clear(playerId, chosenSessionId)
            removeSession(chosenSessionId)
            throw t
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
                ensureActiveBinding(entry, clientIdentity.playerId)

                JoinResult.Success(
                    sessionId = sessionId,
                    playerId = clientIdentity.playerId,
                    opponentSpecId = opponentSpecId,
                    nickname = nickname,
                    roundSnapshot = joinResponse.roundSnapshot,
                )
            }

            is JoinResponse.Rejected -> {
                if (isNewSession) {
                    removeSession(sessionId)
                }
                // Best-effort cleanup of any poisonous binding on rejection
                playerActiveSessionIndex.clear(clientIdentity.playerId, sessionId)
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
                // Best-effort cleanup of any poisonous binding on timeout
                playerActiveSessionIndex.clear(clientIdentity.playerId, sessionId)
                JoinResult.Failure(
                    sessionId = sessionId,
                    errorCode = "join_timeout",
                    message = "session join timed out",
                )
            }
        }
    }

    suspend fun startNextRound(sessionId: Uuid, playerId: Uuid, commandId: Uuid): CommandResult {
        val actor = (actors[sessionId] as? SessionEntry.Active)?.actor
            ?: return CommandResult.Failure(
                sessionId = sessionId,
                errorCode = "session_not_found",
                message = "session not found",
            )

        scheduleIdleTimeout(sessionId)
        if (!actor.submit(
                SessionCommand.StartNextRound(
                    sessionId = sessionId,
                    playerId = playerId,
                    commandId = commandId
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

    suspend fun submitAnswer(
        sessionId: Uuid,
        playerId: Uuid,
        roundId: Uuid,
        commandId: Uuid,
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
                    commandId = commandId,
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

    fun getActiveSession(
        playerId: Uuid,
        activeSessionIdHint: Uuid?,
    ): ActiveSessionSnapshot? {
        if (activeSessionIdHint != null) {
            val hintEntry = getOwnedActiveEntry(playerId, activeSessionIdHint)
            if (hintEntry != null) {
                // Heal binding if missing so terminateActiveSessionByOwner() works correctly
                ensureActiveBinding(hintEntry, playerId)
                val hintBinding = playerActiveSessionIndex.get(playerId)
                val opponentSpecId = hintBinding?.opponentSpecId ?: hintEntry.actor.opponentSpecId
                val createdAt = hintBinding?.createdAt ?: clock.instant()
                return ActiveSessionSnapshot(
                    sessionId = activeSessionIdHint,
                    opponentSpecId = opponentSpecId,
                    createdAt = createdAt,
                )
            }

            val binding = playerActiveSessionIndex.get(playerId)
            if (binding?.sessionId == activeSessionIdHint) {
                playerActiveSessionIndex.clear(playerId, activeSessionIdHint)
                logger.debug(
                    "Cleared stale active session hint {} for player {}",
                    activeSessionIdHint,
                    playerId
                )
            }
        }

        val binding = playerActiveSessionIndex.get(playerId) ?: return null
        val entry = getOwnedActiveEntry(playerId, binding.sessionId)
        if (entry == null) {
            playerActiveSessionIndex.clear(playerId, binding.sessionId)
            logger.debug(
                "Cleared stale active session binding {} for player {}",
                binding.sessionId,
                playerId
            )
            return null
        }

        return ActiveSessionSnapshot(
            sessionId = binding.sessionId,
            opponentSpecId = binding.opponentSpecId,
            createdAt = binding.createdAt,
        )
    }

    fun terminateActiveSessionByOwner(playerId: Uuid): Uuid? {
        val binding = playerActiveSessionIndex.takeByOwner(playerId)
        if (binding == null) {
            logger.debug("No active session to terminate for player {}", playerId)
            return null
        }

        // SECURITY: Double-check ownership of the actual session entry
        // This is defense-in-depth in case a poisonous binding somehow exists
        val entry = actors[binding.sessionId] as? SessionEntry.Active
        if (entry != null && entry.ownerPlayerId != playerId) {
            logger.warn(
                "Player {} attempted to terminate session {} but it's owned by {}",
                playerId,
                binding.sessionId,
                entry.ownerPlayerId
            )
            return null
        }

        logger.debug("Terminating active session {} for player {}", binding.sessionId, playerId)
        removeSession(binding.sessionId)
        return binding.sessionId
    }

    private fun scheduleIdleTimeout(sessionId: Uuid) {
        idleTimeoutJobs[sessionId]?.cancel()
        idleTimeoutJobs[sessionId] = supervisorScope.launch {
            delay(IDLE_TIMEOUT_MS)
            (actors[sessionId] as? SessionEntry.Active)?.actor?.submitCritical(
                SessionCommand.Timeout(reason = "timeout")
            )
        }
    }

    private fun scheduleMaxLifespan(sessionId: Uuid) {
        maxLifespanJobs[sessionId]?.cancel()
        maxLifespanJobs[sessionId] = supervisorScope.launch {
            delay(MAX_LIFESPAN_MS)
            // Route through actor to ensure SessionResolved is emitted
            (actors[sessionId] as? SessionEntry.Active)?.actor?.submitCritical(
                SessionCommand.Timeout(reason = "max_lifespan")
            )
        }
    }

    private fun removeSession(sessionId: Uuid) {
        idleTimeoutJobs.remove(sessionId)?.cancel()
        maxLifespanJobs.remove(sessionId)?.cancel()
        when (val entry = actors.remove(sessionId)) {
            is SessionEntry.Active -> {
                playerActiveSessionIndex.clear(entry.ownerPlayerId, sessionId)
                logger.debug("Cleared active session binding for player {} session {}", entry.ownerPlayerId, sessionId)
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
            val sessionId: Uuid,
            val actor: SessionActor,
            val mode: GameMode,
            val permit: ActiveSessionLimiter.Permit,
            val ownerPlayerId: Uuid,
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

    private fun entryForBinding(
        binding: PlayerActiveSessionIndex.Binding,
        playerId: Uuid,
    ): SessionEntry? {
        return when (val entry = actors[binding.sessionId]) {
            is SessionEntry.Active -> {
                if (entry.ownerPlayerId == playerId) entry else null
            }

            is SessionEntry.Creating -> entry
            null -> null
        }
    }

    private fun getOwnedActiveEntry(playerId: Uuid, sessionId: Uuid): SessionEntry.Active? {
        val entry = actors[sessionId] as? SessionEntry.Active ?: return null
        return if (entry.ownerPlayerId == playerId) entry else null
    }

    private fun ensureActiveBinding(entry: SessionEntry.Active, playerId: Uuid) {
        if (entry.ownerPlayerId != playerId) return
        val existing = playerActiveSessionIndex.get(playerId)
        if (existing?.sessionId == entry.sessionId) return
        val binding = PlayerActiveSessionIndex.Binding(
            sessionId = entry.sessionId,
            opponentSpecId = entry.actor.opponentSpecId,
            createdAt = clock.instant(),
        )
        playerActiveSessionIndex.getOrReserve(playerId, binding)
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
