package io.github.ceracharlescc.versuslm.internal.application.port

import io.github.ceracharlescc.versuslm.internal.domain.entity.GameMode
import java.time.Instant
import java.util.UUID

internal interface ResultsRepository {
    /**
     * Saves a completed game session result.
     *
     * @param result The session result to save
     */
    suspend fun saveResult(result: SessionResult)

    /**
     * Retrieves the leaderboard entries.
     *
     * @param limit Maximum number of entries to return
     * @return List of leaderboard entries sorted by best score descending
     */
    suspend fun getLeaderboard(limit: Int = 10): List<LeaderboardEntry>
}

internal data class SessionResult(
    val sessionId: UUID,
    val mode: GameMode,
    val llmProfileName: String,
    val humanNickname: String,
    val humanScore: Double,
    val llmScore: Double,
    val humanWon: Boolean,
    val durationMs: Long,
    val completedAt: Instant
)

data class LeaderboardEntry(
    val rank: Int,
    val nickname: String,
    val bestScore: Double,
    val bestTimeMs: Long,
    val gamesPlayed: Int
)
