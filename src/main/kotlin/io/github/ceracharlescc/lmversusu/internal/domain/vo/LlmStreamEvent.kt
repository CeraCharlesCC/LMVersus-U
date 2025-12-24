package io.github.ceracharlescc.lmversusu.internal.domain.vo

internal sealed class LlmStreamEvent {
    data class ReasoningDelta(
        val deltaText: String,
        val emittedTokenCount: Int,
        val totalTokenCount: Int
    ) : LlmStreamEvent()

    data class FinalAnswer(
        val answer: Answer
    ) : LlmStreamEvent()
}