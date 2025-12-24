package io.github.ceracharlescc.lmversusu.internal.domain.entity

import java.util.UUID

internal data class Player(
    val playerId: UUID,
    val type: PlayerType,
    val nickname: String
) {
    enum class PlayerType {
        HUMAN,
        LLM
    }
}
