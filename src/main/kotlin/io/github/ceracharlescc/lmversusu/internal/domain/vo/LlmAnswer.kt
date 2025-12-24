package io.github.ceracharlescc.lmversusu.internal.domain.vo

internal data class LlmAnswer(
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null
)
