package io.github.ceracharlescc.versuslm.internal.presentation.ktor.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

internal fun Route.leaderboardRoutes() {
    get("/leaderboard") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
        val clampedLimit = limit.coerceIn(1, 100)

        val placeholderEntries = listOf(
            LeaderboardEntryDto(
                rank = 1,
                nickname = "Placeholder Player",
                bestScore = 100.0,
                bestTimeMs = 5000,
                gamesPlayed = 1
            )
        )

        val response = LeaderboardResponse(
            entries = placeholderEntries,
            total = placeholderEntries.size,
            limit = clampedLimit
        )
        call.respond(HttpStatusCode.OK, response)
    }
}

@Serializable
internal data class LeaderboardResponse(
    val entries: List<LeaderboardEntryDto>,
    val total: Int,
    val limit: Int
)

@Serializable
internal data class LeaderboardEntryDto(
    val rank: Int,
    val nickname: String,
    val bestScore: Double,
    val bestTimeMs: Long,
    val gamesPlayed: Int
)
