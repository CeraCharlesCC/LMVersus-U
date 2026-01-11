package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.ktor.http.*
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.request.*

fun Application.configureHttpCache() {

    install(CachingHeaders) {
        options { call, outgoingContent ->
            val path = call.request.path()

            val ct = outgoingContent.contentType?.withoutParameters()
            if (ct == ContentType.Text.Html) {
                return@options CachingOptions(CacheControl.NoCache(null))
            }

            if (path.endsWith(".css") || path.endsWith(".js")) {
                return@options CachingOptions(
                    CacheControl.MaxAge(
                        maxAgeSeconds = 60 * 60 * 24,
                        visibility = CacheControl.Visibility.Public
                    )
                )
            }

            null
        }
    }
}
