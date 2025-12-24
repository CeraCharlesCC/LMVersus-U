package io.github.ceracharlescc.versuslm.internal.presentation.ktor.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

internal fun Route.heartbeatRoutes() {
    get("/heartbeat") {
        val response = HeartbeatResponse(
            status = "ok",
            timestamp = System.currentTimeMillis()
        )
        call.respond(HttpStatusCode.OK, response)
    }
}

@Serializable
internal data class HeartbeatResponse(
    val status: String,
    val timestamp: Long
)
