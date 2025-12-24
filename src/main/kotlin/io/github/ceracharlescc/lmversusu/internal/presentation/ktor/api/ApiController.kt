package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.github.ceracharlescc.lmversusu.internal.application.usecase.GetLeaderboardUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.entity.LeaderboardEntry
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ApiController @Inject constructor(
    private val getLeaderboardUseCase: GetLeaderboardUseCase
) {
    suspend fun getLeaderboard(limit: Int): ApiResponse<LeaderboardResponse> {
        if (limit !in 1..100) return ApiResponse.BadRequest("limit must be between 1 and 100")

        return when (val result = getLeaderboardUseCase.execute(limit)) {
            is GetLeaderboardUseCase.Result.Success -> {
                ApiResponse.Ok(
                    LeaderboardResponse(
                        entries = result.entries,
                        total = result.entries.size,
                        limit = limit
                    )
                )
            }
            is GetLeaderboardUseCase.Result.Failure -> {
                ApiResponse.ServiceUnavailable("leaderboard is temporarily unavailable")
            }
        }
    }
}

@Serializable
internal data class LeaderboardResponse(
    val entries: List<LeaderboardEntry>,
    val total: Int,
    val limit: Int
)

