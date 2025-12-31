package io.github.ceracharlescc.lmversusu.internal.presentation.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import javax.naming.AuthenticationException
import kotlin.uuid.Uuid

@Serializable
data class ErrorBody(
    val error: String,
    val message: String? = null,
    val details: List<String>? = null,
    val errorId: String? = null,
)

internal fun Application.configureRouting(logger: Logger) {
    install(StatusPages) {
        // 404 for unknown routes (no exception is thrown)
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorBody(error = "not_found", message = "route not found")
            )
        }

        // 400: malformed JSON / body could not be converted to your DTO
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody(error = "bad_request", message = "invalid request body")
            )
        }

        // 415: client sent an unsupported Content-Type (often shows up as this exception)
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ErrorBody(error = "unsupported_media_type", message = "unsupported content type")
            )
        }

        // 400: missing query/path/form parameter
        exception<MissingRequestParameterException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody(
                    error = "bad_request",
                    message = "missing parameter: ${cause.parameterName}"
                )
            )
        }

        // 422: failed RequestValidation rules
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorBody(
                    error = "validation_failed",
                    message = "request validation failed",
                    details = cause.reasons
                )
            )
        }

        // 400: explicit bad request
        exception<BadRequestException> { call, cause ->
            logger.debug(
                "BadRequestException at {} {}: {}",
                call.request.httpMethod.value,
                call.request.path(),
                cause.message
            )
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorBody(error = "bad_request", message = "invalid request")
            )
        }

        // 401: authentication failure
        exception<AuthenticationException> { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, ErrorBody(error = "unauthorized"))
        }

        // Catch-all 500
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause

            val trackId = Uuid.random().toString()
            logger.error(
                "Unhandled exception, trackId={}, method={}, path={}",
                trackId, call.request.httpMethod.value, call.request.path(), cause
            )

            if (!call.response.isCommitted) {
                call.response.headers.append("X-Error-Id", trackId)
                runCatching {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorBody(error = "internal_server_error", errorId = trackId)
                    )
                }.getOrElse {
                    // ultra-safe fallback if JSON serialization fails
                    call.respondText(
                        "internal_server_error (errorId=$trackId)",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }
        }
    }
}

