package io.github.ceracharlescc.versuslm.internal.domain.vo

internal data class VerificationOutcome(
    val correct: Boolean,
    val score: Double? = null,
    val explanation: String? = null
)
