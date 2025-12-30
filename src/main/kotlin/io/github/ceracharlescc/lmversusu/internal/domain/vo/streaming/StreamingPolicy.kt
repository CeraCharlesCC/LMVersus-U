package io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming

import kotlinx.serialization.Serializable

@Serializable
internal data class StreamingPolicy(
    val revealDelayMs: Long = 0,
    val targetTokensPerSecond: Int = 0,
    val burstMultiplierOnFinal: Double = 1.0,
    val maxBufferedChars: Int = 200_000,
    val chunkDelay: Int = 0,
)
