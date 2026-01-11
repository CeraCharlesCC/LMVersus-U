package io.github.ceracharlescc.lmversusu.internal

import io.github.ceracharlescc.lmversusu.internal.di.AppComponent
import io.github.ceracharlescc.lmversusu.internal.di.DaggerAppComponent
import io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor.*
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.apiV1Routes
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.configureRouting
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.configureSockets
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.gameRoutes
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing

fun main(args: Array<String>) {
    EngineMain.main(args)
}

internal fun Application.module(
    appConfigOverride: AppConfig? = null,
    appComponentProvider: ((AppConfig) -> AppComponent)? = null,
) {
    val appConfig = appConfigOverride ?: ConfigLoader.load()
    val appComponent = appComponentProvider?.invoke(appConfig)
        ?: DaggerAppComponent.builder()
            .appConfig(appConfig)
            .build()

    configureSockets()
    configureSerialization()
    configureAdministration()
    configureMonitoring()
    configureSecurity(appConfig.sessionCrypto)
    configureFrontend()
    configureHTTP(appConfig.serverConfig)
    configureHttpCache()

    configureRouting(appComponent.logger())

    routing {
        apiV1Routes(
            apiController = appComponent.apiController(),
        )
        gameRoutes(
            gameController = appComponent.gameController(),
            gameEventBus = appComponent.gameEventBus(),
            frameMapper = appComponent.gameEventFrameMapper(),
            sessionLimitConfig = appConfig.sessionLimitConfig,
            serverConfig = appConfig.serverConfig
        )
    }

}

