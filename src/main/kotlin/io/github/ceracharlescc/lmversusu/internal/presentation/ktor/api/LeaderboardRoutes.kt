package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.response.ApiResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get


internal fun Route.leaderboardRoutes(apiController: ApiController) {
    get("/leaderboard") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
        val clampedLimit = limit.coerceIn(1, 100)

        when (val response = apiController.getLeaderboard(clampedLimit)) {
            is ApiResponse.Ok -> call.respond(HttpStatusCode.OK, response.body)
            is ApiResponse.BadRequest -> call.respond(HttpStatusCode.BadRequest, mapOf("message" to response.message))
            is ApiResponse.NotFound -> call.respond(HttpStatusCode.NotFound, mapOf("message" to response.message))
            is ApiResponse.ServiceUnavailable -> call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("message" to response.message)
            )

            is ApiResponse.InternalError -> call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("message" to response.message)
            )
        }
    }
}