package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.vo.ClientIdentity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admission gate for creating new game sessions.
 *
 * This component enforces:
 * - **Max active sessions** per [GameMode] (concurrency control).
 * - **Rate limits / quotas** for session creation using fixed windows:
 *   - Per-person limits (keyed by IP and by player id) for daily quotas and short fixed windows.
 *   - Global limits per mode for daily quotas and short fixed windows.
 *
 * The main entrypoint is [tryAdmitCreation]. When creation is admitted it returns a [Permit].
 * **Call [Permit.close] exactly once when the session is terminated** (or when creation fails after admission)
 * so resources (e.g., active session slots) are released.
 *
 * Thread-safety:
 * - Active-session limiting is handled via per-mode [Semaphore]s.
 * - Window-limits are checked/consumed atomically across multiple keys via a synchronized critical section.
 *
 * @param clock Clock used to measure windows consistently (test-friendly).
 * @param sessionLimitConfig Global session limit configuration (e.g., daily window duration).
 * @param modeContextFor Maps a [GameMode] to its [ModeLimitContext] (labels + per-mode limits).
 */
internal class SessionCreationGate(
    clock: Clock,
    private val sessionLimitConfig: AppConfig.SessionLimitConfig,
    private val modeContextFor: (GameMode) -> ModeLimitContext,
) {
    /**
     * Result of a session creation admission attempt.
     *
     * - [Admitted] includes a [Permit] that must be closed to release acquired capacity.
     * - [Denied] includes a stable `errorCode` and a human-readable message.
     */
    sealed interface Decision {
        /** Admission succeeded; caller must eventually [Permit.close] the returned [permit]. */
        data class Admitted(val permit: Permit) : Decision

        /** Admission rejected due to limits; no capacity is reserved. */
        data class Denied(val errorCode: String, val message: String) : Decision
    }

    /**
     * A handle for capacity reserved during session creation.
     *
     * Implementations must be safe to close more than once (idempotent) because callers may
     * invoke cleanup from multiple failure paths.
     */
    interface Permit : AutoCloseable {
        /** Releases any reserved capacity. Must be safe to call multiple times. */
        override fun close()
    }

    /**
     * Derived per-mode context used for limit evaluation and user-facing messages.
     *
     * @property limitConfig The per-mode limit configuration (quotas and rate limits).
     * @property modeLabel Label used in keys and messages (e.g., "premium", "lightweight").
     */
    data class ModeLimitContext(
        val limitConfig: AppConfig.ModeLimitConfig,
        val modeLabel: String,
    )

    private val activeSessionLimiter = ActiveSessionLimiter { mode ->
        modeContextFor(mode).limitConfig.maxActiveSessions
    }
    private val windowLimitRegistry = WindowLimitRegistry(clock)

    /**
     * Attempts to admit a new session creation for [clientIdentity] in the given [mode].
     *
     * Admission algorithm:
     * 1. Acquire an "active session" slot for the mode (if configured).
     * 2. Check/consume all configured window limits (per-person and global).
     * 3. If any window limit fails, the active-session slot is released immediately and the attempt is denied.
     *
     * Error codes:
     * - `"session_limit_exceeded"`: quota hit (daily or max-active).
     * - `"rate_limited"`: short-window rate limit hit.
     *
     * @return [Decision.Admitted] with a [Permit] to be closed on session termination, or [Decision.Denied].
     */
    fun tryAdmitCreation(clientIdentity: ClientIdentity, mode: GameMode): Decision {
        val limitContext = modeContextFor(mode)
        val activePermit = activeSessionLimiter.tryAcquire(mode)
            ?: return Decision.Denied(
                errorCode = "session_limit_exceeded",
                message = "too many active ${limitContext.modeLabel} sessions",
            )

        val windowFailure = tryAcquireWindowLimits(clientIdentity, limitContext)
        if (windowFailure != null) {
            // Release the active-session slot since the overall admission failed.
            activePermit.close()
            return Decision.Denied(
                errorCode = windowFailure.errorCode,
                message = windowFailure.message,
            )
        }

        return Decision.Admitted(CompositePermit(listOf(activePermit)))
    }

    /**
     * Builds and attempts to acquire all window-based limits for a given client and mode.
     *
     * Keys are generated per mode using [ModeLimitContext.modeLabel] and are split by:
     * - Per-person, keyed by IP (`person:ip`) and by player id (`person:player`)
     * - Global, keyed by `global`
     * - Daily vs short "window" buckets
     *
     * Notes:
     * - Blank IP addresses are normalized to `"unknown"` to keep key-space bounded.
     * - A return value of `null` means all limits were successfully acquired and consumed.
     *
     * @return A [WindowLimitRegistry.LimitFailure] if any configured limit is exceeded, otherwise `null`.
     */
    private fun tryAcquireWindowLimits(
        clientIdentity: ClientIdentity,
        limitContext: ModeLimitContext,
    ): WindowLimitRegistry.LimitFailure? {
        val ipAddress = clientIdentity.ipAddress.ifBlank { "unknown" }
        val playerKey = clientIdentity.playerId.toString()
        val modePrefix = "mode:${limitContext.modeLabel}"
        val dailyWindowMillis = sessionLimitConfig.dailyWindowMillis

        val entries = buildList {
            // Per-person daily quota (counts both by IP and by player id).
            val perPersonDailyLimit = limitContext.limitConfig.perPersonDailyLimit
            if (perPersonDailyLimit > 0) {
                add(
                    WindowLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:ip:$ipAddress:daily",
                        windowMillis = dailyWindowMillis,
                        limit = perPersonDailyLimit,
                        failure = WindowLimitRegistry.LimitFailure(
                            errorCode = "session_limit_exceeded",
                            message = "daily ${limitContext.modeLabel} session limit reached",
                        ),
                    )
                )
                add(
                    WindowLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:player:$playerKey:daily",
                        windowMillis = dailyWindowMillis,
                        limit = perPersonDailyLimit,
                        failure = WindowLimitRegistry.LimitFailure(
                            errorCode = "session_limit_exceeded",
                            message = "daily ${limitContext.modeLabel} session limit reached",
                        ),
                    )
                )
            }

            // Per-person short window rate limit (counts both by IP and by player id).
            val perPersonWindowLimit = limitContext.limitConfig.perPersonWindowLimit
            val perPersonWindowMillis = limitContext.limitConfig.perPersonWindowMillis
            if (perPersonWindowLimit > 0 && perPersonWindowMillis > 0) {
                add(
                    WindowLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:ip:$ipAddress:window",
                        windowMillis = perPersonWindowMillis,
                        limit = perPersonWindowLimit,
                        failure = WindowLimitRegistry.LimitFailure(
                            errorCode = "rate_limited",
                            message = "session creation rate limited",
                        ),
                    )
                )
                add(
                    WindowLimitRegistry.LimitEntry(
                        key = "$modePrefix:person:player:$playerKey:window",
                        windowMillis = perPersonWindowMillis,
                        limit = perPersonWindowLimit,
                        failure = WindowLimitRegistry.LimitFailure(
                            errorCode = "rate_limited",
                            message = "session creation rate limited",
                        ),
                    )
                )
            }

            // Global short window rate limit per mode.
            val globalWindowLimit = limitContext.limitConfig.globalWindowLimit
            val globalWindowMillis = limitContext.limitConfig.globalWindowMillis
            if (globalWindowLimit > 0 && globalWindowMillis > 0) {
                add(
                    WindowLimitRegistry.LimitEntry(
                        key = "$modePrefix:global:window",
                        windowMillis = globalWindowMillis,
                        limit = globalWindowLimit,
                        failure = WindowLimitRegistry.LimitFailure(
                            errorCode = "rate_limited",
                            message = "global ${limitContext.modeLabel} session rate limit reached",
                        ),
                    )
                )
            }

            // Global daily quota per mode.
            val globalDailyLimit = limitContext.limitConfig.globalDailyLimit
            if (globalDailyLimit > 0) {
                add(
                    WindowLimitRegistry.LimitEntry(
                        key = "$modePrefix:global:daily",
                        windowMillis = dailyWindowMillis,
                        limit = globalDailyLimit,
                        failure = WindowLimitRegistry.LimitFailure(
                            errorCode = "session_limit_exceeded",
                            message = "global daily ${limitContext.modeLabel} session limit reached",
                        ),
                    )
                )
            }
        }

        return windowLimitRegistry.tryAcquire(entries)
    }
}

/**
 * A [SessionCreationGate.Permit] that closes multiple underlying permits.
 *
 * This is used when admission requires reserving multiple independent resources.
 * Closing is idempotent.
 */
private class CompositePermit(
    private val permits: List<SessionCreationGate.Permit>
) : SessionCreationGate.Permit {
    private val closed = AtomicBoolean(false)

    /** Closes all underlying permits at most once. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            permits.forEach { it.close() }
        }
    }
}

/**
 * Enforces a maximum number of active sessions per [GameMode] using semaphores.
 *
 * If the configured maximum for a mode is `<= 0`, the limiter treats it as "unlimited"
 * and returns a no-op permit.
 */
private class ActiveSessionLimiter(
    private val maxActiveByMode: (GameMode) -> Int,
) {
    private val semaphores = ConcurrentHashMap<GameMode, Semaphore>()

    /**
     * A permit representing an acquired active-session slot.
     *
     * Closing the permit releases the slot back to the underlying semaphore.
     */
    interface Permit : SessionCreationGate.Permit

    /**
     * Attempts to acquire an active-session slot for [mode].
     *
     * @return A [Permit] if a slot was acquired (or a no-op permit if unlimited),
     * or `null` if the mode is at capacity.
     */
    fun tryAcquire(mode: GameMode): Permit? {
        val maxActive = maxActiveByMode(mode)
        if (maxActive <= 0) {
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
     * Returns (or creates) the semaphore associated with [mode].
     *
     * Note: if [maxActiveByMode] can change at runtime, this method does not resize
     * existing semaphores; it only uses the value at first creation.
     */
    private fun semaphoreFor(mode: GameMode, maxActive: Int): Semaphore =
        semaphores.computeIfAbsent(mode) { Semaphore(maxActive) }

    /**
     * A permit that releases a semaphore slot exactly once.
     */
    private class SemaphorePermit(private val semaphore: Semaphore) : Permit {
        private val released = AtomicBoolean(false)

        /** Releases the semaphore slot at most once. */
        override fun close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release()
            }
        }
    }

    /**
     * A no-op permit used when max-active limiting is disabled.
     */
    private object NoOpPermit : Permit {
        override fun close() = Unit
    }
}

/**
 * Registry of window-based counters used to enforce per-key fixed-window limits.
 *
 * Each counter is stored in a Caffeine cache keyed by string. Entries expire after
 * an inactivity period ([evictionDuration]) to avoid unbounded memory usage.
 *
 * The public operation [tryAcquire] performs an atomic "check then consume" across
 * all provided entries, ensuring you don't partially consume some counters if a later
 * counter would fail.
 *
 * @param clock Clock used to evaluate windows.
 * @param evictionDuration Cache eviction duration after access. Should be longer than the largest window.
 */
private class WindowLimitRegistry(
    private val clock: Clock,
    evictionDuration: Duration = Duration.ofHours(25),
) {
    private val lock = Any()
    private val counters: Cache<String, WindowCounter> = Caffeine.newBuilder()
        .expireAfterAccess(evictionDuration)
        .build()

    /**
     * Describes a limit violation in a stable and client-consumable way.
     *
     * @property errorCode Stable error code (e.g., `"rate_limited"`, `"session_limit_exceeded"`).
     * @property message Human-readable message suitable for API responses/logs.
     */
    data class LimitFailure(
        val errorCode: String,
        val message: String,
    )

    /**
     * Declarative specification of a single window limit to be enforced.
     *
     * @property key Unique counter key.
     * @property windowMillis Window size in milliseconds. A value `<= 0` disables this entry.
     * @property limit Maximum allowed count within the window. A value `<= 0` disables this entry.
     * @property failure Failure returned if this entry cannot be consumed.
     */
    data class LimitEntry(
        val key: String,
        val windowMillis: Long,
        val limit: Int,
        val failure: LimitFailure,
    )

    /**
     * Atomically attempts to consume 1 unit from each provided [entries].
     *
     * Behavior:
     * - If [entries] is empty, returns `null`.
     * - If any entry cannot be consumed, returns that entry's [LimitEntry.failure] and consumes nothing.
     * - If all entries can be consumed, increments all counters and returns `null`.
     *
     * Thread-safety: the entire check/consume sequence is synchronized to guarantee atomicity
     * across multiple keys.
     *
     * @return A [LimitFailure] if admission should be denied, otherwise `null`.
     */
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

    /**
     * Returns the counter for [key], creating it if needed.
     */
    private fun counterFor(key: String): WindowCounter =
        counters.get(key) { WindowCounter() }

    /**
     * A fixed-window counter.
     *
     * The counter resets when `now - windowStart >= windowMillis`.
     * Window start is anchored to the time of the first event after a rotation.
     */
    private class WindowCounter {
        private var windowStart: Instant = Instant.EPOCH
        private var count: Int = 0

        /**
         * Checks whether another unit can be consumed in the current window.
         *
         * @return `true` if consuming would keep `count + 1 <= limit`, or if the entry is disabled.
         */
        fun canConsume(now: Instant, windowMillis: Long, limit: Int): Boolean {
            if (limit <= 0 || windowMillis <= 0) return true
            rotateWindowIfNeeded(now, windowMillis)
            return count + 1 <= limit
        }

        /**
         * Consumes one unit in the current window (no-op if disabled).
         */
        fun consume(now: Instant, windowMillis: Long) {
            if (windowMillis <= 0) return
            rotateWindowIfNeeded(now, windowMillis)
            count++
        }

        /**
         * Rotates the window if the current window has expired.
         *
         * When rotating, the window start is set to [now] and the counter is reset to 0.
         */
        private fun rotateWindowIfNeeded(now: Instant, windowMillis: Long) {
            if (Duration.between(windowStart, now).toMillis() >= windowMillis) {
                windowStart = now
                count = 0
            }
        }
    }
}
