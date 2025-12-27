package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamSeq
import java.time.Instant
import kotlin.uuid.Uuid

internal sealed class GameEvent {
    abstract val sessionId: Uuid

    data class SessionCreated(
        override val sessionId: Uuid,
        val joinCode: String
    ) : GameEvent()

    data class PlayerJoined(
        override val sessionId: Uuid,
        val playerId: Uuid,
        val nickname: String
    ) : GameEvent()

    data class RoundStarted(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val roundNumber: Int,
        val questionPrompt: String,
        val choices: List<String>?,
        val releasedAt: Instant,
        val handicapMs: Long,
        val deadlineAt: Instant,
        val nonceToken: String
    ) : GameEvent()

    data class RoundResolved(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val correctAnswer: Answer,
        val humanCorrect: Boolean,
        val llmCorrect: Boolean,
        val humanScore: Double,
        val llmScore: Double,
        val winner: String
    ) : GameEvent()

    data class SessionCompleted(
        override val sessionId: Uuid,
        val humanTotalScore: Double,
        val llmTotalScore: Double,
        val humanWon: Boolean
    ) : GameEvent()

    data class SessionError(
        override val sessionId: Uuid,
        val errorCode: String,
        val message: String
    ) : GameEvent()

    data class SubmissionReceived(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val playerType: Player.PlayerType,
    ) : GameEvent()

    data class LlmThinking(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val progress: Int? = null,
    ) : GameEvent()

    data class LlmReasoningDelta(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val deltaText: String,
        val seq: StreamSeq,
    ) : GameEvent()

    data class LlmReasoningTruncated(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val droppedChars: Int,
    ) : GameEvent()

    data class LlmFinalAnswer(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val answer: LlmAnswer,
    ) : GameEvent()

    data class LlmStreamError(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val message: String,
    ) : GameEvent()
}
