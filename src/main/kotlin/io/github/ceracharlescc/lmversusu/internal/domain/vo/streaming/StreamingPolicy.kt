package io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming

import kotlinx.serialization.Serializable

@Serializable
internal data class StreamingPolicy(
    val revealDelayMs: Long,
    val targetTokensPerSecond: Int,
    val burstMultiplierOnFinal: Double,
    val maxBufferedChars: Int,
)
