package io.github.ceracharlescc.lmversusu.internal.infrastructure.ktor

import io.ktor.http.*
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.*
import io.ktor.server.http.content.isStaticContent
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.request.*

fun Application.configureHttpCache() {
    val oneYearSeconds = 60 * 60 * 24 * 365

    install(CachingHeaders) {
        options { call, outgoingContent ->
            if (!call.isStaticContent()) return@options null
            if (call.request.httpMethod != HttpMethod.Get && call.request.httpMethod != HttpMethod.Head) {
                return@options null
            }

            val path = call.request.path()

            val ct = outgoingContent.contentType?.withoutParameters()
            if (ct == ContentType.Text.Html) {
                return@options CachingOptions(CacheControl.NoCache(null))
            }

            if (path.startsWith("/i18n/") || path.contains("/i18n/")) {
                return@options CachingOptions(CacheControl.NoStore(null))
            }

            CachingOptions(
                CacheControl.MaxAge(
                    maxAgeSeconds = oneYearSeconds,
                    visibility = CacheControl.Visibility.Public
                )
            )
        }
    }
}
