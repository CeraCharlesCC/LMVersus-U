package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal class ActiveSessionLimiter(
    private val maxActiveByMode: (GameMode) -> Int,
) {
    private val semaphores = ConcurrentHashMap<GameMode, Semaphore>()

    fun tryAcquire(mode: GameMode): Boolean {
        val maxActive = maxActiveByMode(mode)
        if (maxActive <= 0) return true
        return semaphoreFor(mode, maxActive).tryAcquire()
    }

    fun release(mode: GameMode) {
        val maxActive = maxActiveByMode(mode)
        if (maxActive <= 0) return
        semaphoreFor(mode, maxActive).release()
    }

    private fun semaphoreFor(mode: GameMode, maxActive: Int): Semaphore =
        semaphores.computeIfAbsent(mode) { Semaphore(maxActive) }
}
