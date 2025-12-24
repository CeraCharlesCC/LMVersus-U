package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ServiceSession
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie

internal fun Application.configureSecurity(sessionCryptoConfig: AppConfig.SessionCryptoConfig) {
    val keys = sessionCryptoConfig.requireKeys()

    install(Sessions) {
        cookie<ServiceSession>("LMVU_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = sessionCryptoConfig.enableSecureCookie
            cookie.extensions["SameSite"] = "lax"
            transform(
                SessionTransportTransformerEncrypt(
                    keys.encryptionKey,
                    keys.signKey
                )
            )
        }
    }
}
