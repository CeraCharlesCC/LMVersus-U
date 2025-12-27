package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

internal interface LlmPlayerGateway {

    /**
     * Streams the answer from the LLM based on the provided [roundContext].
     *
     * @param roundContext The context of the round including question ID, prompt, choices, and opponent spec.
     * @return A flow of [LlmStreamEvent] representing the streaming events from the LLM.
     */
    fun streamAnswer(roundContext: RoundContext): Flow<LlmStreamEvent>

    /**
     * Gets the final answer from the LLM based on the provided [roundContext].
     *
     * @param roundContext The context of the round including question ID, prompt, choices, and opponent spec.
     * @return An [LlmAnswer] representing the final answer from the LLM.
     */
    suspend fun getAnswer(roundContext: RoundContext): LlmAnswer
}

internal data class RoundContext(
    val questionId: Uuid,
    val questionPrompt: String,
    val choices: List<String>?,
    val opponentSpec: OpponentSpec,
)