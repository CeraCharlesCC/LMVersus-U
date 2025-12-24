package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.github.flaxoos.ktor.server.plugins.ratelimiter.RateLimiting
import io.github.flaxoos.ktor.server.plugins.ratelimiter.implementations.TokenBucket
import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlin.time.Duration.Companion.seconds

internal fun Application.configureAdministration() {
    install(RateLimiting) {
        rateLimiter {
            type = TokenBucket::class
            capacity = 100
            rate = 10.seconds
        }
    }
}
