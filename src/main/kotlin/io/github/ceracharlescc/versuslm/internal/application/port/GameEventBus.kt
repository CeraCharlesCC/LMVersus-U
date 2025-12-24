package io.github.ceracharlescc.versuslm.internal.application.port

import io.github.ceracharlescc.versuslm.internal.domain.entity.GameEvent
import java.util.UUID

internal interface GameEventBus {
    /**
     * Publishes a game event to all subscribers.
     *
     * @param event The event to publish
     */
    suspend fun publish(event: GameEvent)

    /**
     * Subscribes to game events for a specific session.
     *
     * @param sessionId The session to subscribe to
     * @param listener The listener to receive events
     */
    fun subscribe(
        sessionId: UUID,
        listener: GameEventListener
    )

    /**
     * Unsubscribes a listener from a session.
     *
     * @param sessionId The session to unsubscribe from
     * @param listener The listener to remove
     */
    fun unsubscribe(
        sessionId: UUID,
        listener: GameEventListener
    )
}

internal fun interface GameEventListener {
    suspend fun onEvent(event: GameEvent)
}
