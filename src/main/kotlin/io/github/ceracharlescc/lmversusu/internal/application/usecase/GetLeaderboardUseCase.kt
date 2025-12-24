package io.github.ceracharlescc.lmversusu.internal.application.usecase

import io.github.ceracharlescc.lmversusu.internal.application.port.ResultsRepository
import io.github.ceracharlescc.versuslm.internal.domain.entity.LeaderboardEntry
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GetLeaderboardUseCase @Inject constructor(
    private val resultsRepository: ResultsRepository
) {
    suspend fun execute(limit: Int = 10): Result {
        return try {
            val entries = resultsRepository.getLeaderboard(limit)
            Result.Success(entries)
        } catch (exception: Exception) {
            Result.Failure(exception)
        }
    }

    sealed interface Result {
        data class Success(val entries: List<LeaderboardEntry>) : Result
        data class Failure(val cause: Throwable) : Result
    }
}
