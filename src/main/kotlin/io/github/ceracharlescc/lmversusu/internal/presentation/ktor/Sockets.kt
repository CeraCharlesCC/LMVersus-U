package io.github.ceracharlescc.lmversusu.internal.presentation.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets

internal fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 45_000
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
}
