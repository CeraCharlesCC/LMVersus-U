package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Limits the number of active game sessions per [GameMode].
 *
 * @param maxActiveByMode Returns the maximum number of active sessions allowed for a given [GameMode].
 *
 * Important: [maxActiveByMode] must be stable for the lifetime of the process (it must not change at runtime).
 */
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
        semaphores[mode]?.release()
    }

    private fun semaphoreFor(mode: GameMode, maxActive: Int): Semaphore =
        semaphores.computeIfAbsent(mode) { Semaphore(maxActive) }
}
