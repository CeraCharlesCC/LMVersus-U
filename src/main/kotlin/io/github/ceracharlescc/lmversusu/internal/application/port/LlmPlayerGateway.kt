package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmRoundContext
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

internal interface LlmPlayerGateway {

    /**
     * Streams the answer from the LLM based on the provided [roundContext].
     *
     * @param roundContext The context of the round including question ID, prompt, choices, and LLM profile.
     * @return A flow of [LlmStreamEvent] representing the streaming events from the LLM.
     */
    fun streamAnswer(roundContext: LlmRoundContext): Flow<LlmStreamEvent>

    /**
     * Gets the final answer from the LLM based on the provided [roundContext].
     *
     * @param roundContext The context of the round including question ID, prompt, choices, and LLM profile.
     * @return An [LlmAnswer] representing the final answer from the LLM.
     */
    suspend fun getAnswer(roundContext: LlmRoundContext): LlmAnswer
}

internal data class LlmAnswer(
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null
)

