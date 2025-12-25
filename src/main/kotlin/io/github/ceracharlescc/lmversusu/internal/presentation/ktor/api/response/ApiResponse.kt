package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.response

internal sealed interface ApiResponse<out T> {
    data class Ok<T>(val body: T) : ApiResponse<T>
    data class BadRequest(val message: String) : ApiResponse<Nothing>
    data class NotFound(val message: String = "Not found") : ApiResponse<Nothing>
    data class ServiceUnavailable(val message: String) : ApiResponse<Nothing>
    data class InternalError(val message: String = "Internal error") : ApiResponse<Nothing>
}