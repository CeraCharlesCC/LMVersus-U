package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.ktor.server.application.Application
import io.ktor.server.http.content.ignoreFiles
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.routing
import java.io.File


internal fun Application.configureFrontend() {
    val localWebDir = File("web")
    routing {
        if (localWebDir.exists() && localWebDir.isDirectory) {
            staticFiles("/", localWebDir)
        }
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