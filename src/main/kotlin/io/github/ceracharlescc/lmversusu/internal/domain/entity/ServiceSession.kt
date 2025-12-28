package io.github.ceracharlescc.lmversusu.internal.domain.entity

import kotlinx.serialization.Serializable

@Serializable
internal data class ServiceSession(
    val playerId: String,
    val issuedAtEpochMs: Long,
)