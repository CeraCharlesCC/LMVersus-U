package io.github.ceracharlescc.lmversusu.internal.utils

/**
 * A simple, non-thread-safe rate limiter for a single connection.
 * It relies on being called from a single thread, like Ktor's WebSocket `incoming` loop.
 */
class ConnectionRateLimiter(
    private val windowMillis: Long,
    private val maxMessages: Int,
) {
    private var windowStartMs: Long = System.currentTimeMillis()
    private var count: Int = 0

    fun tryConsume(): Boolean {
        if (windowMillis <= 0 || maxMessages <= 0) return true
        val nowMs = System.currentTimeMillis()
        if (nowMs - windowStartMs >= windowMillis) {
            windowStartMs = nowMs
            count = 0
        }
        if (count >= maxMessages) return false
        count++
        return true
    }
}
