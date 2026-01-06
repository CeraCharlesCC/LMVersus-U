package io.github.ceracharlescc.lmversusu.internal.domain.repository

import java.time.Instant
import kotlin.uuid.Uuid

internal interface PlayerActiveSessionRepository {
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