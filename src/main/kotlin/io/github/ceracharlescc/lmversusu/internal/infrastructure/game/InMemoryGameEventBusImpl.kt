@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventListener
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

@Singleton
internal class InMemoryGameEventBusImpl @Inject constructor(
    private val logger: Logger
) : GameEventBus {
    private val listenersBySession = ConcurrentHashMap<Uuid, CopyOnWriteArraySet<GameEventListener>>()
    private val authorizedPlayersBySession = ConcurrentHashMap<Uuid, Uuid>()

    override suspend fun publish(event: GameEvent) {
        val sessionId = event.sessionId
        val set = listenersBySession[sessionId] ?: return

        for (listener in set.toList()) {
            try {
                listener.onEvent(event)
            } catch (exception: CancellationException) {
                throw exception
            } catch (t: Throwable) {
                logger.error("Listener failed for session {}", sessionId, t)
                set.remove(listener)
            }
        }
    }

    override fun authorizePlayer(sessionId: Uuid, playerId: Uuid) {
        authorizedPlayersBySession[sessionId] = playerId
    }

    override fun subscribe(sessionId: Uuid, playerId: Uuid, listener: GameEventListener): Boolean {
        val authorized = authorizedPlayersBySession[sessionId]
        if (authorized == null || authorized != playerId) {
            logger.warn("Unauthorized subscription attempt: session={}, playerId={}", sessionId, playerId)
            return false
        }
        listenersBySession.computeIfAbsent(sessionId) { CopyOnWriteArraySet() }.add(listener)
        return true
    }

    override fun unsubscribe(sessionId: Uuid, listener: GameEventListener) {
        listenersBySession[sessionId]?.remove(listener)
    }

    override fun revokeSession(sessionId: Uuid) {
        authorizedPlayersBySession.remove(sessionId)
        listenersBySession.remove(sessionId)
    }
}
