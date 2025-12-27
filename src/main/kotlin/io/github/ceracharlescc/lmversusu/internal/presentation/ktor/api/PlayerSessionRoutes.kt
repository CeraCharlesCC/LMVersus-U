package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api

import io.github.ceracharlescc.lmversusu.internal.domain.entity.ServiceSession
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
internal data class PlayerSessionResponse(
    val playerId: String,
    val issuedAtEpochMs: Long,
)

@OptIn(ExperimentalUuidApi::class)
internal fun Route.playerSessionRoutes() {
    get("/player/session") {
        val existingSession = call.sessions.get<ServiceSession>()

        if (existingSession != null) {
            // Session already exists, return it
            call.respond(
                PlayerSessionResponse(
                    playerId = existingSession.playerId,
                    issuedAtEpochMs = existingSession.issuedAtEpochMs,
                )
            )
        } else {
            // Create new session
            val now = System.currentTimeMillis()
            val newSession = ServiceSession(
                playerId = Uuid.random().toString(),
                issuedAtEpochMs = now,
            )

            call.sessions.set(newSession)

            call.respond(
                PlayerSessionResponse(
                    playerId = newSession.playerId,
                    issuedAtEpochMs = newSession.issuedAtEpochMs,
                )
            )
        }
    }
}
