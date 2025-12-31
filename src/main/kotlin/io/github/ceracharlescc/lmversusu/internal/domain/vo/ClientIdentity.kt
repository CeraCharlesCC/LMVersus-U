package io.github.ceracharlescc.lmversusu.internal.domain.vo

import kotlin.uuid.Uuid

internal data class ClientIdentity(
    val playerId: Uuid,
    val ipAddress: String,
)
