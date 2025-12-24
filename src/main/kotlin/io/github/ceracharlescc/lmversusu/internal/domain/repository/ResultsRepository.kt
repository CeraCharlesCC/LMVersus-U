package io.github.ceracharlescc.lmversusu.internal.domain.repository

import io.github.ceracharlescc.lmversusu.internal.domain.entity.LeaderboardEntry
import io.github.ceracharlescc.lmversusu.internal.domain.entity.SessionResult

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