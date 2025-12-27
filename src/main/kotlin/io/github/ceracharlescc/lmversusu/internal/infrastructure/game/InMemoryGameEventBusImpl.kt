package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventListener
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class InMemoryGameEventBusImpl @Inject constructor() : GameEventBus {
    private val listenersBySession = ConcurrentHashMap<Uuid, CopyOnWriteArraySet<GameEventListener>>()

    override suspend fun publish(event: GameEvent) {
        val listeners = listenersBySession[event.sessionId]?.toList().orEmpty()
        for (listener in listeners) {
            listener.onEvent(event)
        }
    }

    override fun subscribe(sessionId: Uuid, listener: GameEventListener) {
        listenersBySession.computeIfAbsent(sessionId) { CopyOnWriteArraySet() }.add(listener)
    }

    override fun unsubscribe(sessionId: Uuid, listener: GameEventListener) {
        listenersBySession[sessionId]?.remove(listener)
    }
}
