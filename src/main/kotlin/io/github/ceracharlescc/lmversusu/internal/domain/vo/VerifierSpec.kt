package io.github.ceracharlescc.lmversusu.internal.domain.vo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed class VerifierSpec {
    @Serializable
    @SerialName("multiple_choice")
    data class MultipleChoice(val correctIndex: Int) : VerifierSpec()

    @Serializable
    @SerialName("integer_range")
    data class IntegerRange(
        val correctValue: Int,
        val minValue: Int = Int.MIN_VALUE,
        val maxValue: Int = Int.MAX_VALUE
    ) : VerifierSpec()

    @Serializable
    @SerialName("free_response")
    data class FreeResponse(
        val rubric: String? = null,
        val expectedKeywords: List<String> = emptyList()
    ) : VerifierSpec()
}
