package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.modelsRoutes(apiController: ApiController) {
    get("/models") {
        val mode = call.request.queryParameters["mode"]

        when (val response = apiController.getAvailableModels(mode)) {
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
