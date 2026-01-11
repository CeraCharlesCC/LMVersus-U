package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface WsGameFrame {
    val sessionId: String?
}

@Serializable
@SerialName("session_joined")
internal data class WsSessionJoined(
    override val sessionId: String,
    val playerId: String,
    val nickname: String,
    val opponentSpecId: String,
) : WsGameFrame

@Serializable
@SerialName("session_error")
internal data class WsSessionError(
    override val sessionId: String? = null,
    val errorCode: String,
    val message: String,
) : WsGameFrame

@Serializable
@SerialName("player_joined")
internal data class WsPlayerJoined(
    override val sessionId: String,
    val playerId: String,
    val nickname: String,
) : WsGameFrame

@Serializable
@SerialName("round_started")
internal data class WsRoundStarted(
    override val sessionId: String,
    val questionId: String,
    val roundId: String,
    val roundNumber: Int,
    val questionPrompt: String,
    val choices: List<String>?,
    val expectedAnswerType: String,
    val releasedAtEpochMs: Long,
    val handicapMs: Long,
    val deadlineAtEpochMs: Long,
    val nonceToken: String,
    val humanAnswer: Answer? = null,
) : WsGameFrame

@Serializable
@SerialName("round_resolved")
internal data class WsRoundResolved(
    override val sessionId: String,
    val roundId: String,
    val correctAnswer: Answer,
    val humanCorrect: Boolean,
    val llmCorrect: Boolean,
    val humanScore: Double,
    val llmScore: Double,
    val winner: String,
    val reason: String,
) : WsGameFrame

@Serializable
@SerialName("llm_thinking")
internal data class WsLlmThinking(
    override val sessionId: String,
    val roundId: String,
) : WsGameFrame

@Serializable
@SerialName("llm_reasoning_delta")
internal data class WsLlmReasoningDelta(
    override val sessionId: String,
    val roundId: String,
    val deltaText: String,
    val seq: Long,
) : WsGameFrame

@Serializable
@SerialName("llm_reasoning_truncated")
internal data class WsLlmReasoningTruncated(
    override val sessionId: String,
    val roundId: String,
    val droppedChars: Int,
) : WsGameFrame

@Serializable
@SerialName("llm_stream_error")
internal data class WsLlmStreamError(
    override val sessionId: String,
    val roundId: String,
    val message: String,
) : WsGameFrame

@Serializable
@SerialName("llm_final_answer")
internal data class WsLlmFinalAnswer(
    override val sessionId: String,
    val roundId: String,
    val finalAnswer: Answer,
    val reasoningSummary: String? = null,
    val confidenceScore: Double? = null,
) : WsGameFrame

@Serializable
@SerialName("llm_answer_lock_in")
internal data class WsLlmAnswerLockIn(
    override val sessionId: String,
    val roundId: String,
) : WsGameFrame

@Serializable
@SerialName("llm_reasoning_ended")
internal data class WsLlmReasoningEnded(
    override val sessionId: String,
    val roundId: String,
) : WsGameFrame

@Serializable
@SerialName("llm_reasoning_reveal")
internal data class WsLlmReasoningReveal(
    override val sessionId: String,
    val roundId: String,
    val fullReasoning: String,
) : WsGameFrame

@Serializable
@SerialName("session_resolved")
internal data class WsSessionResolved(
    override val sessionId: String,
    val state: String,
    val reason: String,
    val humanTotalScore: Double,
    val llmTotalScore: Double,
    val winner: String,
    val roundsPlayed: Int,
    val totalRounds: Int,
    val resolvedAtEpochMs: Long,
    val durationMs: Long,
) : WsGameFrame

@Serializable
@SerialName("session_terminated")
internal data class WsSessionTerminated(
    override val sessionId: String,
    val reason: String,
) : WsGameFrame
