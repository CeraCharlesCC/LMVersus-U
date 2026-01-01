package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class SessionLimitRegistry(
    private val clock: Clock,
    evictionDuration: Duration = Duration.ofHours(25),
) {
    private val lock = Any()
    private val counters: Cache<String, WindowCounter> = Caffeine.newBuilder()
        .expireAfterAccess(evictionDuration)
        .build()

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
        counters.get(key) { WindowCounter() }

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
