package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WsGameFrame

@Serializable
@SerialName("session_joined")
internal data class WsSessionJoined(
    val sessionId: String,
    val playerId: String,
    val nickname: String,
    val opponentSpecId: String,
) : WsGameFrame

@Serializable
@SerialName("session_error")
internal data class WsSessionError(
    val sessionId: String? = null,
    val errorCode: String,
    val message: String,
) : WsGameFrame

@Serializable
@SerialName("player_joined")
internal data class WsPlayerJoined(
    val sessionId: String,
    val playerId: String,
    val nickname: String,
) : WsGameFrame
@Serializable
@SerialName("round_started")
internal data class WsRoundStarted(
    val sessionId: String,
    val questionId: String,
    val roundId: String,
    val roundNumber: Int,
    val questionPrompt: String,
    val choices: List<String>?,
    val releasedAtEpochMs: Long,
    val handicapMs: Long,
    val deadlineAtEpochMs: Long,
    val nonceToken: String,
) : WsGameFrame

@Serializable
@SerialName("round_resolved")
internal data class WsRoundResolved(
    val sessionId: String,
    val roundId: String,
    val correctAnswer: Answer,
    val humanCorrect: Boolean,
    val llmCorrect: Boolean,
    val humanScore: Double,
    val llmScore: Double,
    val winner: String,
) : WsGameFrame

@Serializable
@SerialName("llm_reasoning_delta")
internal data class WsLlmReasoningDelta(
    val sessionId: String,
    val roundId: String,
    val deltaText: String,
    val seq: Long,
) : WsGameFrame

@Serializable
@SerialName("llm_reasoning_truncated")
internal data class WsLlmReasoningTruncated(
    val sessionId: String,
    val roundId: String,
    val droppedChars: Int,
) : WsGameFrame

@Serializable
@SerialName("llm_stream_error")
internal data class WsLlmStreamError(
    val sessionId: String,
    val roundId: String,
    val message: String,
) : WsGameFrame

@Serializable
@SerialName("llm_final_answer")
internal data class WsLlmFinalAnswer(
    val sessionId: String,
    val roundId: String,
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null,
) : WsGameFrame
