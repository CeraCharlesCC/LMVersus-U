package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.ktor.server.application.Application
import io.ktor.server.http.content.ignoreFiles
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.routing.routing


internal fun Application.configureFrontend() {
    routing {
        singlePageApplication {
            useResources = true
            filesPath = "web"
            defaultPage = "index.html"

            ignoreFiles { path ->
                path.startsWith("api/") || path.startsWith("ws/")
            }
        }
    }
}