package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.*
import kotlinx.serialization.Serializable

@Serializable
internal data class UserSession(val count: Int = 0)

internal fun Application.configureSecurity() {
    install(Sessions) {
        cookie<UserSession>("MY_SESSION") {
            cookie.extensions["SameSite"] = "lax"
        }
    }
    routing {
        get("/session/increment") {
            val session = call.sessions.get<UserSession>()
                ?: UserSession()
            call.sessions.set(session.copy(count = session.count + 1))
            call.respondText("Counter is ${session.count}. Refresh to increment.")
        }
    }
}
