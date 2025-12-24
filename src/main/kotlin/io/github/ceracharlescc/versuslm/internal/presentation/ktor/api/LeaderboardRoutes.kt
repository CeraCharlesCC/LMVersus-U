package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.github.ceracharlescc.versuslm.internal.domain.entity.LeaderboardEntry
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
            LeaderboardEntry(
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
    val entries: List<LeaderboardEntry>,
    val total: Int,
    val limit: Int
)

