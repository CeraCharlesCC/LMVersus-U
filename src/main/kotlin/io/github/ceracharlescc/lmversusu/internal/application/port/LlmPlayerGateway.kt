package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

internal interface LlmPlayerGateway {
    /**
     * Streams the LLM answer process:
     * - multiple reasoning deltas
     * - one final answer event
     */
    fun streamAnswer(roundContext: RoundContext): Flow<LlmStreamEvent>

    /**
     * Non-streaming convenience (optional).
     * In implementations you can derive this by collecting streamAnswer().
     */
    suspend fun getAnswer(roundContext: RoundContext): LlmAnswer
}

internal data class RoundContext(
    val questionId: Uuid,
    val questionPrompt: String,
    val choices: List<String>?,
    val llmProfile: LlmProfile
)

internal data class LlmAnswer(
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null
)

internal sealed class LlmStreamEvent {
    data class ReasoningDelta(
        val deltaText: String,
        val emittedTokenCount: Int,
        val totalTokenCount: Int
    ) : LlmStreamEvent()

    data class FinalAnswer(val answer: LlmAnswer) : LlmStreamEvent()
}