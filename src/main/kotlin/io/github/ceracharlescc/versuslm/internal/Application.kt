package io.github.ceracharlescc.versuslm.internal

import io.github.ceracharlescc.versuslm.internal.presentation.ktor.api.apiV1Routes
import io.github.ceracharlescc.versuslm.internal.presentation.ktor.configureRouting
import io.github.ceracharlescc.versuslm.internal.presentation.ktor.configureSockets
import io.github.ceracharlescc.versuslm.internal.presentation.ktor.game.gameRoutes
import io.github.ceracharlescc.versuslm.internal.infrastructure.ktor.configureAdministration
import io.github.ceracharlescc.versuslm.internal.infrastructure.ktor.configureHTTP
import io.github.ceracharlescc.versuslm.internal.infrastructure.ktor.configureMonitoring
import io.github.ceracharlescc.versuslm.internal.infrastructure.ktor.configureSecurity
import io.github.ceracharlescc.versuslm.internal.infrastructure.ktor.configureSerialization
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing

fun main(args: Array<String>) {
    EngineMain.main(args)
}

internal fun Application.module() {
    configureSockets()
    configureSerialization()
    configureAdministration()
    configureMonitoring()
    configureSecurity()
    configureHTTP()

    configureRouting()

    routing {
        apiV1Routes()
        gameRoutes()
    }
}

