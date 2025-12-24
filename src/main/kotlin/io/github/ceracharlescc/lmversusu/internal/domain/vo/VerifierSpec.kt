package io.github.ceracharlescc.lmversusu.internal.domain.vo

internal sealed class VerifierSpec {
    data class MultipleChoice(val correctIndex: Int) : VerifierSpec()

    data class IntegerRange(
        val correctValue: Int,
        val minValue: Int = Int.MIN_VALUE,
        val maxValue: Int = Int.MAX_VALUE
    ) : VerifierSpec()

    data class FreeResponse(
        val rubric: String? = null,
        val expectedKeywords: List<String> = emptyList()
    ) : VerifierSpec()
}
