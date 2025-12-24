package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Score
import java.time.Instant
import kotlin.uuid.Uuid

internal data class GameSession(
    val sessionId: Uuid,
    val joinCode: String,
    val mode: GameMode,
    val llmProfile: LlmProfile,
    val players: PlayerSet,
    val rounds: List<Round> = emptyList(),
    val state: SessionState = SessionState.WAITING,
    val createdAt: Instant = Instant.now()
) {
    companion object {
        const val TOTAL_ROUNDS = 3
    }

    val currentRound: Round?
        get() = rounds.lastOrNull { it.isInProgress }

    val currentRoundNumber: Int
        get() = rounds.size

    val isCompleted: Boolean
        get() = rounds.size == TOTAL_ROUNDS && rounds.all { !it.isInProgress }

    fun calculateTotalScores(): Pair<Score, Score> {
        val humanScore = rounds.sumOf { it.result?.humanOutcome?.score?.points ?: 0.0 }
        val llmScore = rounds.sumOf { it.result?.llmOutcome?.score?.points ?: 0.0 }
        return Score(humanScore) to Score(
            llmScore
        )
    }
}

enum class SessionState {
    /** Waiting for players to join */
    WAITING,

    /** Game is currently in progress */
    IN_PROGRESS,

    /** Game has been completed */
    COMPLETED,

    /** Game was canceled or abandoned */
    CANCELLED
}

enum class GameMode {
    /** Uses precomputed LLM outputs for offline play */
    LIGHTWEIGHT,

    /** Uses live API calls to LLM providers */
    PREMIUM
}