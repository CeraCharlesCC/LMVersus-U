package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.github.ceracharlescc.lmversusu.internal.domain.entity.ServiceSession
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.util.hex

internal fun Application.configureSecurity() {
    install(Sessions) {
        cookie<ServiceSession>("LMVU_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = false
            cookie.extensions["SameSite"] = "lax"
            transform(
                SessionTransportTransformerEncrypt(
                    hex("ENC_KEY"),
                    hex("SIGN_KEY")
                )
            )
        }
    }
}
