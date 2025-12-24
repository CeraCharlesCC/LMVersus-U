package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository

import io.github.ceracharlescc.lmversusu.internal.domain.entity.LeaderboardEntry
import io.github.ceracharlescc.lmversusu.internal.domain.entity.SessionResult
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class InMemoryResultsRepositoryImpl @Inject constructor() : ResultsRepository {
    private val bestByNickname = ConcurrentHashMap<String, LeaderboardEntry>()

    override suspend fun saveResult(result: SessionResult) {
        val candidate = LeaderboardEntry(
            rank = 0,
            userId = result.humanUserId,
            nickname = result.humanNickname,
            bestScore = result.humanScore,
            bestTimeMs = result.durationMs,
            gamesPlayed = 1
        )

        bestByNickname.merge(result.humanNickname, candidate) { existing, incoming ->
            val better = when {
                incoming.bestScore > existing.bestScore -> incoming
                incoming.bestScore < existing.bestScore -> existing
                else -> if (incoming.bestTimeMs < existing.bestTimeMs) incoming else existing
            }
            better.copy(gamesPlayed = existing.gamesPlayed + 1)
        }
    }

    override suspend fun getLeaderboard(limit: Int): List<LeaderboardEntry> {
        return bestByNickname.values
            .sortedWith(compareByDescending<LeaderboardEntry> { it.bestScore }.thenBy { it.bestTimeMs })
            .take(limit)
            .mapIndexed { index, entry -> entry.copy(rank = index + 1) }
    }
}