package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Represents a single acquired slot in the limiter.
     *
     * Must be released exactly once via [close], but the close operation is idempotent
     * and safe to call multiple times.
     */
    interface Permit : AutoCloseable {
        override fun close()
    }

    /**
     * Diagnostic statistics for a single [GameMode].
     */
    data class LimiterStats(
        val max: Int,
        val inUse: Int,
        val available: Int,
    )

    /**
     * Attempts to acquire a session slot for the given [mode].
     *
     * @return A [Permit] if acquisition succeeds, or `null` if the limit would be exceeded.
     */
    fun tryAcquire(mode: GameMode): Permit? {
        val maxActive = maxActiveByMode(mode)
        if (maxActive <= 0) {
            // No limit configured - return a no-op permit
            return NoOpPermit
        }
        val semaphore = semaphoreFor(mode, maxActive)
        return if (semaphore.tryAcquire()) {
            SemaphorePermit(semaphore)
        } else {
            null
        }
    }

    /**
     * Returns diagnostic statistics for all modes that have been accessed.
     */
    fun snapshot(): Map<GameMode, LimiterStats> {
        return semaphores.entries.associate { (mode, semaphore) ->
            val max = maxActiveByMode(mode)
            val available = semaphore.availablePermits()
            mode to LimiterStats(
                max = max,
                inUse = max - available,
                available = available,
            )
        }
    }

    private fun semaphoreFor(mode: GameMode, maxActive: Int): Semaphore =
        semaphores.computeIfAbsent(mode) { Semaphore(maxActive) }

    /**
     * A permit backed by a [Semaphore]. Uses an [AtomicBoolean] to ensure
     * the semaphore is released exactly once, making [close] idempotent.
     */
    private class SemaphorePermit(private val semaphore: Semaphore) : Permit {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release()
            }
        }
    }

    /**
     * A no-op permit for modes with no configured limit.
     * Close is always safe and has no effect.
     */
    private object NoOpPermit : Permit {
        override fun close() {
            // No-op: no limit was enforced, nothing to release
        }
    }
}
