@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Result of attempting to join a game session.
 */
internal sealed interface JoinResult {
    data class Success(
        val sessionId: Uuid,
        val playerId: Uuid,
        val opponentSpecId: String,
        val nickname: String,
        val roundSnapshot: GameEvent.RoundStarted? = null,
    ) : JoinResult

    data class Failure(
        val sessionId: Uuid? = null,
        val errorCode: String,
        val message: String,
    ) : JoinResult
}

/**
 * Result of a session command (e.g., start round, submit answer).
 */
internal sealed interface CommandResult {
    data class Success(val sessionId: Uuid) : CommandResult
    data class Failure(
        val sessionId: Uuid? = null,
        val errorCode: String,
        val message: String,
    ) : CommandResult
}

/**
 * Result of touching (refreshing idle timeout) a session.
 */
internal sealed interface TouchResult {
    data object Success : TouchResult
    data object SessionNotFound : TouchResult
}

/**
 * Snapshot of an active session for external queries.
 */
internal data class ActiveSessionSnapshot(
    val sessionId: Uuid,
    val opponentSpecId: String,
    val createdAt: Instant,
)
