package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimiter
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

internal fun Application.configureAdministration() {
    install(RateLimit) {
        global {
            rateLimiter(limit = 200, refillPeriod = 10.seconds)

            modifyResponse { call, state ->
                call.response.headers.append("X-RateLimit-Measured-by", "Calls")

                if (state is RateLimiter.State.Available) {
                    val resetInMillis = max(0L, state.refillAtTimeMillis - System.currentTimeMillis())
                    call.response.headers.append("X-RateLimit-Reset-Millis", resetInMillis.toString())
                }
            }
        }
    }
}
