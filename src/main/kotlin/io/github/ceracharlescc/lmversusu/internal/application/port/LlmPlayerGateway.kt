package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

internal interface LlmPlayerGateway {

    /**
     * Streams the answer from the LLM based on the provided [roundContext].
     *
     * @param roundContext The context of the round including question ID, prompt, choices, and LLM profile.
     * @return A flow of [LlmStreamEvent] representing the streaming events from the LLM.
     */
    fun streamAnswer(roundContext: RoundContext): Flow<LlmStreamEvent>

    /**
     * Gets the final answer from the LLM based on the provided [roundContext].
     *
     * @param roundContext The context of the round including question ID, prompt, choices, and LLM profile.
     * @return An [LlmAnswer] representing the final answer from the LLM.
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