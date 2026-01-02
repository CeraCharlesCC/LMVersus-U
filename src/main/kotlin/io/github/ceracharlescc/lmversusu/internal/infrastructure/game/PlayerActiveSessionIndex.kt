@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

internal interface PlayerActiveSessionIndex {
    data class Binding(
        val sessionId: Uuid,
        val opponentSpecId: String,
        val createdAt: Instant,
    )

    fun get(playerId: Uuid): Binding?

    fun getOrReserve(playerId: Uuid, newBinding: Binding): Binding

    fun clear(playerId: Uuid, sessionId: Uuid)

    fun takeByOwner(playerId: Uuid): Binding?
}

@Singleton
internal class InMemoryPlayerActiveSessionIndex @Inject constructor() : PlayerActiveSessionIndex {
    private val bindings = ConcurrentHashMap<Uuid, PlayerActiveSessionIndex.Binding>()

    override fun get(playerId: Uuid): PlayerActiveSessionIndex.Binding? {
        return bindings[playerId]
    }

    override fun getOrReserve(
        playerId: Uuid,
        newBinding: PlayerActiveSessionIndex.Binding,
    ): PlayerActiveSessionIndex.Binding {
        return bindings.compute(playerId) { _, existing -> existing ?: newBinding }!!
    }

    override fun clear(playerId: Uuid, sessionId: Uuid) {
        bindings.computeIfPresent(playerId) { _, existing ->
            if (existing.sessionId == sessionId) null else existing
        }
    }

    override fun takeByOwner(playerId: Uuid): PlayerActiveSessionIndex.Binding? {
        var taken: PlayerActiveSessionIndex.Binding? = null
        bindings.compute(playerId) { _, existing ->
            if (existing == null) null
            else {
                taken = existing
                null
            }
        }
        return taken
    }
}
