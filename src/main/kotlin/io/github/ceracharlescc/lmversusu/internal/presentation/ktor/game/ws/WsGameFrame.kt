package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WsGameFrame {
    val type: String
}

@Serializable
@SerialName("llm_reasoning_delta")
internal data class WsLlmReasoningDelta(
    override val type: String = "llm_reasoning_delta",
    val sessionId: String,
    val roundId: String,
    val deltaText: String,
    val seq: Long,
) : WsGameFrame

@Serializable
@SerialName("llm_reasoning_truncated")
internal data class WsLlmReasoningTruncated(
    override val type: String = "llm_reasoning_truncated",
    val sessionId: String,
    val roundId: String,
    val droppedChars: Int,
) : WsGameFrame

@Serializable
@SerialName("llm_final_answer")
internal data class WsLlmFinalAnswer(
    override val type: String = "llm_final_answer",
    val sessionId: String,
    val roundId: String,
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null,
) : WsGameFrame