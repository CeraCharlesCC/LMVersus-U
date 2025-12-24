package io.github.ceracharlescc.lmversusu.internal.domain.entity

import java.time.Instant
import java.util.UUID

internal sealed class GameEvent {
    abstract val sessionId: UUID

    data class SessionCreated(
        override val sessionId: UUID,
        val joinCode: String
    ) : GameEvent()

    data class PlayerJoined(
        override val sessionId: UUID,
        val playerId: UUID,
        val nickname: String
    ) : GameEvent()

    data class RoundStarted(
        override val sessionId: UUID,
        val roundId: UUID,
        val roundNumber: Int,
        val questionPrompt: String,
        val choices: List<String>?,
        val releasedAt: Instant,
        val handicapMs: Long,
        val deadlineAt: Instant,
        val nonceToken: String
    ) : GameEvent()

    data class LlmThinking(
        override val sessionId: UUID,
        val roundId: UUID,
        val progress: Int? = null
    ) : GameEvent()

    data class LlmSubmitted(
        override val sessionId: UUID,
        val roundId: UUID
    ) : GameEvent()

    data class HumanSubmitted(
        override val sessionId: UUID,
        val roundId: UUID
    ) : GameEvent()

    data class RoundResolved(
        override val sessionId: UUID,
        val roundId: UUID,
        val correctAnswer: String,
        val humanCorrect: Boolean,
        val llmCorrect: Boolean,
        val humanScore: Double,
        val llmScore: Double,
        val winner: String
    ) : GameEvent()

    data class SessionCompleted(
        override val sessionId: UUID,
        val humanTotalScore: Double,
        val llmTotalScore: Double,
        val humanWon: Boolean
    ) : GameEvent()

    data class SessionError(
        override val sessionId: UUID,
        val errorCode: String,
        val message: String
    ) : GameEvent()
}