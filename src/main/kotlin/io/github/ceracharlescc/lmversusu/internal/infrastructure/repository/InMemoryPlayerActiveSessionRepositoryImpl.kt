@file:OptIn(ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository

import io.github.ceracharlescc.lmversusu.internal.domain.repository.PlayerActiveSessionRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Singleton
internal class InMemoryPlayerActiveSessionRepository @Inject constructor() : PlayerActiveSessionRepository {
    private val bindings = ConcurrentHashMap<Uuid, PlayerActiveSessionRepository.Binding>()

    override fun get(playerId: Uuid): PlayerActiveSessionRepository.Binding? {
        return bindings[playerId]
    }

    override fun getOrReserve(
        playerId: Uuid,
        newBinding: PlayerActiveSessionRepository.Binding,
    ): PlayerActiveSessionRepository.Binding {
        return bindings.compute(playerId) { _, existing -> existing ?: newBinding }!!
    }

    override fun clear(playerId: Uuid, sessionId: Uuid) {
        bindings.computeIfPresent(playerId) { _, existing ->
            if (existing.sessionId == sessionId) null else existing
        }
    }

    override fun takeByOwner(playerId: Uuid): PlayerActiveSessionRepository.Binding? = bindings.remove(playerId)
}
