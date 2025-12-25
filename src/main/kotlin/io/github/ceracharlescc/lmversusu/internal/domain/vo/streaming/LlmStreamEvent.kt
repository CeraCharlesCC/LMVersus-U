package io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming

internal sealed class LlmStreamEvent {

    data class ReasoningDelta(
        val deltaText: String,
        val emittedTokenCount: Int,
        val totalTokenCount: Int,
    ) : LlmStreamEvent()

    data class FinalAnswer(
        val answer: LlmAnswer,
    ) : LlmStreamEvent()

    data class ReasoningTruncated(
        val droppedChars: Int,
    ) : LlmStreamEvent()

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : LlmStreamEvent()
}