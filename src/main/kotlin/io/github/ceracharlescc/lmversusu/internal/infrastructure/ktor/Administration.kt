package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.github.flaxoos.ktor.server.plugins.ratelimiter.CallVolumeUnit
import io.github.flaxoos.ktor.server.plugins.ratelimiter.RateLimiting
import io.github.flaxoos.ktor.server.plugins.ratelimiter.implementations.TokenBucket
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
import kotlin.time.Duration.Companion.seconds

internal fun Application.configureAdministration() {
    install(RateLimiting) {

        rateLimiter {
            type = TokenBucket::class
            capacity = 200
            rate = 10.seconds
            callVolumeUnit = CallVolumeUnit.Calls()
        }

        rateLimitExceededHandler = { limitedBy ->
            respond(HttpStatusCode.TooManyRequests, "Rate limit exceeded: ${limitedBy.message}")
            response.headers.append("X-RateLimit-Limit", "${limitedBy.rateLimiter.capacity}")
            response.headers.append("X-RateLimit-Measured-by", limitedBy.rateLimiter.callVolumeUnit.name)
            response.headers.append("X-RateLimit-Reset", "${limitedBy.resetIn.inWholeMilliseconds}")
        }
    }
}
