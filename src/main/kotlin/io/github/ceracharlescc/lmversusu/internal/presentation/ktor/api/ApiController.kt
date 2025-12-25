package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.github.ceracharlescc.lmversusu.internal.application.usecase.GetAvailableModelsUseCase
import io.github.ceracharlescc.lmversusu.internal.application.usecase.GetLeaderboardUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.entity.LeaderboardEntry
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.response.ApiResponse
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.response.LeaderboardResponse
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.response.ModelsResponse
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.serializer.OpponentSpecPublicSerializer
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ApiController @Inject constructor(
    private val getLeaderboardUseCase: GetLeaderboardUseCase,
    private val getAvailableModelsUseCase: GetAvailableModelsUseCase
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

    fun getAvailableModels(mode: String?): ApiResponse<ModelsResponse> {
        val parsedMode = mode?.let {
            GameMode.entries.find { m -> m.name.equals(it, ignoreCase = true) }
        }

        if (mode != null && parsedMode == null) {
            return ApiResponse.BadRequest("Invalid mode. Must be LIGHTWEIGHT or PREMIUM.")
        }

        return when (val result = getAvailableModelsUseCase.execute(parsedMode)) {
            is GetAvailableModelsUseCase.Result.Success -> {
                ApiResponse.Ok(ModelsResponse(result.specs))
            }

            is GetAvailableModelsUseCase.Result.Failure -> {
                ApiResponse.ServiceUnavailable("Models unavailable")
            }
        }
    }
}