package io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import kotlinx.serialization.Serializable

@Serializable
internal data class LlmAnswer(
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null,
)