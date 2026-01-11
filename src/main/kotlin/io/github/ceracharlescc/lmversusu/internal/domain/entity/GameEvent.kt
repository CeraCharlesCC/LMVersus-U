@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.RoundResolveReason
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
        val questionId: Uuid,
        val roundId: Uuid,
        val roundNumber: Int,
        val questionPrompt: String,
        val choices: List<String>?,
        val expectedAnswerType: String,
        val releasedAt: Instant,
        val handicapMs: Long,
        val deadlineAt: Instant,
        val nonceToken: String,
        val humanAnswer: Answer? = null,
    ) : GameEvent()

    data class RoundResolved(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val correctAnswer: Answer,
        val humanCorrect: Boolean,
        val llmCorrect: Boolean,
        val humanScore: Double,
        val llmScore: Double,
        val winner: String,
        val reason: RoundResolveReason,
    ) : GameEvent()

    data class SessionCompleted(
        override val sessionId: Uuid,
        val humanTotalScore: Double,
        val llmTotalScore: Double,
        val humanWon: Boolean
    ) : GameEvent()

    data class SessionResolved(
        override val sessionId: Uuid,
        val state: SessionState,
        val reason: String,  // "completed", "timeout", "max_lifespan", "cancelled"
        val humanTotalScore: Double,
        val llmTotalScore: Double,
        val winner: MatchWinner,
        val roundsPlayed: Int,
        val totalRounds: Int,
        val resolvedAt: Instant,
        val durationMs: Long,
    ) : GameEvent()

    enum class MatchWinner {
        HUMAN,
        LLM,
        TIE,
        NONE  // For cancelled matches with no rounds
    }

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

    /**
     * Emitted when the LLM has completed its final answer but the human player
     * has not yet submitted. Used as a UI signal for timing displays or affordances.
     * Idempotent within a round (emitted at most once).
     */
    data class LlmAnswerLockIn(
        override val sessionId: Uuid,
        val roundId: Uuid,
    ) : GameEvent()

    /**
     * Marker event indicating the transition from reasoning to answer content.
     * Emitted when the first answer content chunk is detected.
     * After this, delayed reasoning release stops.
     */
    data class LlmReasoningEnded(
        override val sessionId: Uuid,
        val roundId: Uuid,
    ) : GameEvent()

    /**
     * Full reasoning reveal emitted at round end (when both players have submitted).
     * The frontend should overwrite any partial/delayed reasoning with this complete text.
     */
    data class LlmReasoningReveal(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val fullReasoning: String,
    ) : GameEvent()

    data class SessionTerminated(
        override val sessionId: Uuid,
        val reason: String,  // "timeout", "completed", "cancelled"
    ) : GameEvent()
}
