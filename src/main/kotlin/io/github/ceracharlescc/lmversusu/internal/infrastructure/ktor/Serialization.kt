package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

internal fun Application.configureSerialization() {
    install(ContentNegotiation) {
        gson {
            // Configure Gson settings here if needed
        }
    }
    routing {
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
        get("/json/gson") {
            call.respond(mapOf("hello" to "world"))
        }
    }
}
