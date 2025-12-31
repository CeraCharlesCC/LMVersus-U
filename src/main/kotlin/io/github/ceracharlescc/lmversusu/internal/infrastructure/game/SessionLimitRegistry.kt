package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class SessionLimitRegistry(
    private val clock: Clock,
) {
    private val lock = Any()
    private val counters = ConcurrentHashMap<String, WindowCounter>()

    data class LimitFailure(
        val errorCode: String,
        val message: String,
    )

    data class LimitEntry(
        val key: String,
        val windowMillis: Long,
        val limit: Int,
        val failure: LimitFailure,
    )

    fun tryAcquire(entries: List<LimitEntry>): LimitFailure? {
        if (entries.isEmpty()) return null
        val now = clock.instant()

        return synchronized(lock) {
            for (entry in entries) {
                if (!counterFor(entry.key).canConsume(now, entry.windowMillis, entry.limit)) {
                    return@synchronized entry.failure
                }
            }
            entries.forEach { entry ->
                counterFor(entry.key).consume(now, entry.windowMillis)
            }
            null
        }
    }

    private fun counterFor(key: String): WindowCounter =
        counters.computeIfAbsent(key) { WindowCounter() }

    private class WindowCounter {
        private var windowStart: Instant = Instant.EPOCH
        private var count: Int = 0

        fun canConsume(now: Instant, windowMillis: Long, limit: Int): Boolean {
            if (limit <= 0 || windowMillis <= 0) return true
            rotateWindowIfNeeded(now, windowMillis)
            return count + 1 <= limit
        }

        fun consume(now: Instant, windowMillis: Long) {
            if (windowMillis <= 0) return
            rotateWindowIfNeeded(now, windowMillis)
            count++
        }

        private fun rotateWindowIfNeeded(now: Instant, windowMillis: Long) {
            if (Duration.between(windowStart, now).toMillis() >= windowMillis) {
                windowStart = now
                count = 0
            }
        }
    }
}
