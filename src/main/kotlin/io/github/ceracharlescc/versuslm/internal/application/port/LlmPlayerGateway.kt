package io.github.ceracharlescc.versuslm.internal.application.port

import io.github.ceracharlescc.versuslm.internal.domain.vo.Answer
import io.github.ceracharlescc.versuslm.internal.domain.vo.LlmProfile
import java.util.UUID

internal interface LlmPlayerGateway {
    /**
     * Gets an answer from the LLM for a given round context.
     *
     * @param roundContext Context including the question and profile
     * @return The LLM's answer
     */
    suspend fun getAnswer(roundContext: RoundContext): LlmAnswer
}

internal data class RoundContext(
    val questionId: UUID,
    val questionPrompt: String,
    val choices: List<String>?,
    val llmProfile: LlmProfile
)

internal data class LlmAnswer(
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null
)
