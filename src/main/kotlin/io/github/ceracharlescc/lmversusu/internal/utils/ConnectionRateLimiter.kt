package io.github.ceracharlescc.lmversusu.internal.utils

/**
 * A simple rate limiter for a single connection.
 *
 * <strong>NOT thread-safe:</strong> this class must only be used from a single thread and
 * assumes all calls come from the same execution context (for example, Ktor's WebSocket
 * `incoming` loop). Concurrent access from multiple threads is unsupported and will result
 * in data races and undefined behavior unless external synchronization is provided.
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
