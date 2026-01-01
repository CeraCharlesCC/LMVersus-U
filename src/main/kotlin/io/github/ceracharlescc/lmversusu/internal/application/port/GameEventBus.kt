@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import kotlin.uuid.Uuid

internal interface GameEventBus {
    /**
     * Publishes a game event to all subscribers.
     *
     * @param event The event to publish
     */
    suspend fun publish(event: GameEvent)

    /**
     * Registers an authorized playerId for a session.
     * Only this playerId will be able to subscribe and receive events for the session.
     *
     * @param sessionId The session to authorize for
     * @param playerId The player allowed to receive events
     */
    fun authorizePlayer(sessionId: Uuid, playerId: Uuid)

    /**
     * Subscribes to game events for a specific session.
     * The playerId must have been previously authorized via [authorizePlayer].
     *
     * @param sessionId The session to subscribe to
     * @param playerId The player attempting to subscribe
     * @param listener The listener to receive events
     * @return true if subscription succeeded (playerId is authorized), false otherwise
     */
    fun subscribe(
        sessionId: Uuid,
        playerId: Uuid,
        listener: GameEventListener
    ): Boolean

    /**
     * Unsubscribes a listener from a session.
     *
     * @param sessionId The session to unsubscribe from
     * @param listener The listener to remove
     */
    fun unsubscribe(
        sessionId: Uuid,
        listener: GameEventListener
    )

    /**
     * Cleans up authorization and listeners for a terminated session.
     *
     * @param sessionId The session to revoke
     */
    fun revokeSession(sessionId: Uuid)
}

internal fun interface GameEventListener {
    suspend fun onEvent(event: GameEvent)
}
