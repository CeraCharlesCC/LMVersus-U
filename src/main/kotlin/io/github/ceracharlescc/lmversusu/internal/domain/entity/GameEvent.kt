package io.github.ceracharlescc.lmversusu.internal.domain.entity

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

    data class LlmThinking(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val progress: Int? = null
    ) : GameEvent()

    data class LlmSubmitted(
        override val sessionId: Uuid,
        val roundId: Uuid
    ) : GameEvent()

    data class HumanSubmitted(
        override val sessionId: Uuid,
        val roundId: Uuid
    ) : GameEvent()

    data class RoundResolved(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val correctAnswer: String,
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

    data class LlmReasoningDelta(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val deltaText: String,
        val emittedTokenCount: Int,
        val totalTokenCount: Int
    ) : GameEvent()

    data class LlmFinalAnswer(
        override val sessionId: Uuid,
        val roundId: Uuid,
        val answer: String,
        val confidenceScore: Double? = null
    ) : GameEvent()
}