package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

internal fun Application.configureHTTP(
    serverConfig: AppConfig.ServerConfig
) {
    val isDev = serverConfig.debug

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        allowNonSimpleContentTypes = true

        allowCredentials = true

        if (isDev) {
            allowHost("localhost:8080", schemes = listOf("http"))
            allowHost("127.0.0.1:8080", schemes = listOf("http"))
            allowHost("localhost:2455", schemes = listOf("http"))
            allowHost("127.0.0.1:2455", schemes = listOf("http"))
        } else {
            serverConfig.corsAllowedHosts.forEach { host ->
                allowHost(host, schemes = listOf("https"))
            }
        }
    }
}