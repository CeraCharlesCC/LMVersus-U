package io.github.ceracharlescc.lmversusu.internal.domain.entity

import kotlinx.serialization.Serializable

@Serializable
internal data class ServiceSession(
    val playerId: String,
    val issuedAtEpochMs: Long,
    val activeSessionId: String,
) {
    companion object {
        const val ACTIVE_SESSION_NONE = "null"
    }
}
