package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao

import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Encapsulates the OpenAI Java client configuration and request mapping.
 * Responsible for translating provider streaming responses into [LlmStreamEvent]
 * and for producing a terminal [LlmStreamEvent.FinalAnswer] or [LlmStreamEvent.Error].
 *
 * @param providerName Human-readable name for logging/diagnostics
 * @param apiUrl Base URL of the OpenAI-compatible API endpoint
 * @param apiKey API key for authentication
 */
internal class OpenAIApiDao(
    val providerName: String,
    val apiUrl: String,
    val apiKey: String,
) {

    /**
     * Streams an answer from the LLM API.
     *
     * @param model The model identifier (e.g., "gpt-4o-mini")
     * @param prompt The question prompt to send to the model
     * @param choices Optional list of choices for multiple-choice questions
     * @param temperature Sampling temperature
     * @param maxTokens Maximum tokens in the response
     * @return A flow of [LlmStreamEvent]s representing the streaming response
     */
    fun streamAnswer(
        model: String,
        prompt: String,
        choices: List<String>?,
        temperature: Double,
        maxTokens: Int,
    ): Flow<LlmStreamEvent> {
        // TODO: Implement OpenAI API streaming
        TODO("OpenAIApiDao.streamAnswer not yet implemented")
    }

    /**
     * Gets a complete answer from the LLM API (non-streaming).
     *
     * @param model The model identifier (e.g., "gpt-4o-mini")
     * @param prompt The question prompt to send to the model
     * @param choices Optional list of choices for multiple-choice questions
     * @param temperature Sampling temperature
     * @param maxTokens Maximum tokens in the response
     * @return The complete [LlmAnswer]
     */
    suspend fun getAnswer(
        model: String,
        prompt: String,
        choices: List<String>?,
        temperature: Double,
        maxTokens: Int,
    ): LlmAnswer {
        // TODO: Implement OpenAI API non-streaming request
        TODO("OpenAIApiDao.getAnswer not yet implemented")
    }
}
