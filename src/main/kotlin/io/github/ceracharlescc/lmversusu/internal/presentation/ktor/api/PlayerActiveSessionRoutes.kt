package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.github.ceracharlescc.lmversusu.internal.domain.entity.ServiceSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
internal data class ActiveSessionResponse(
    val activeSessionId: String,
    val opponentSpecId: String,
)

@Serializable
internal data class TerminateActiveSessionResponse(
    val terminatedSessionId: String,
)

@OptIn(ExperimentalUuidApi::class)
internal fun Route.playerActiveSessionRoutes(
    controller: PlayerActiveSessionController,
) {
    get("/player/active-session") {
        val existingSession = call.sessions.get<ServiceSession>()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "auth_required"))

        val playerId = parseUuidOrNull(existingSession.playerId)
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "invalid_player"))

        val activeSessionHint = parseUuidOrNull(existingSession.activeSessionId)
        val activeSession = controller.getActiveSession(playerId, activeSessionHint)
        if (activeSession == null) {
            call.sessions.set(existingSession.copy(activeSessionId = ServiceSession.ACTIVE_SESSION_NONE))
            return@get call.respond(HttpStatusCode.NoContent)
        }

        call.sessions.set(existingSession.copy(activeSessionId = activeSession.sessionId.toString()))
        call.respond(
            HttpStatusCode.OK,
            ActiveSessionResponse(
                activeSessionId = activeSession.sessionId.toString(),
                opponentSpecId = activeSession.opponentSpecId,
            )
        )
    }

    post("/player/active-session/terminate") {
        val existingSession = call.sessions.get<ServiceSession>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "auth_required"))

        val playerId = parseUuidOrNull(existingSession.playerId)
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "invalid_player"))

        val terminatedSessionId = controller.terminateActiveSession(playerId)
        call.sessions.set(existingSession.copy(activeSessionId = ServiceSession.ACTIVE_SESSION_NONE))

        if (terminatedSessionId == null) {
            return@post call.respond(HttpStatusCode.NoContent)
        }

        call.respond(
            HttpStatusCode.OK,
            TerminateActiveSessionResponse(terminatedSessionId = terminatedSessionId.toString())
        )
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun parseUuidOrNull(raw: String?): Uuid? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Uuid.parse(raw) }.getOrNull()
}
