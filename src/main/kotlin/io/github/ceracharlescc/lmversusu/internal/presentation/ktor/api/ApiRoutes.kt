package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.route

internal fun Route.apiV1Routes(apiController: ApiController) {
    route("/api/v1") {
        heartbeatRoutes()
        leaderboardRoutes(apiController)
    }
}
