package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository

import io.github.ceracharlescc.lmversusu.internal.domain.entity.LeaderboardEntry
import io.github.ceracharlescc.lmversusu.internal.domain.entity.SessionResult
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class InMemoryResultsRepositoryImpl @Inject constructor() : ResultsRepository {
    private val bestByUuid = ConcurrentHashMap<Uuid, LeaderboardEntry>()

    override suspend fun saveResult(result: SessionResult) {
        val candidate = LeaderboardEntry(
            sessionId = result.sessionId,
            rank = 0,
            gameMode = result.gameMode,
            difficulty = result.difficulty,
            opponentLlmName = result.llmProfileName,
            questionSetDisplayName = result.questionSetDisplayName,
            userId = result.humanUserId,
            nickname = result.humanNickname,
            bestScore = result.humanScore,
            bestTimeMs = result.durationMs,
        )

        bestByUuid.merge(result.humanUserId, candidate) { existing, incoming ->
            val better = when {
                incoming.bestScore > existing.bestScore -> incoming
                incoming.bestScore < existing.bestScore -> existing
                else -> if (incoming.bestTimeMs < existing.bestTimeMs) incoming else existing
            }
            better.copy()
        }
    }

    override suspend fun getLeaderboard(limit: Int): List<LeaderboardEntry> {
        return bestByUuid.values
            .sortedWith(compareByDescending<LeaderboardEntry> { it.bestScore }.thenBy { it.bestTimeMs })
            .take(limit)
            .mapIndexed { index, entry -> entry.copy(rank = index + 1) }
    }
}