package io.github.ceracharlescc.lmversusu.internal.domain.entity

import kotlin.uuid.Uuid

internal data class Player(
    val playerId: Uuid,
    val type: PlayerType,
    val nickname: String
) {
    enum class PlayerType {
        HUMAN,
        LLM
    }
}
