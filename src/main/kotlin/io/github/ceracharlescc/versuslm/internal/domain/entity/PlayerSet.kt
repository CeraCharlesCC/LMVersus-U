package io.github.ceracharlescc.versuslm.internal.domain.entity

import java.util.UUID

internal data class PlayerSet(
    val human: Player,
    val llm: Player
) {
    init {
        require(human.type == Player.PlayerType.HUMAN) { "Human player must have HUMAN type" }
        require(llm.type == Player.PlayerType.LLM) { "LLM player must have LLM type" }
    }

    fun findById(playerId: UUID): Player? = when (playerId) {
        human.playerId -> human
        llm.playerId -> llm
        else -> null
    }

    fun toList(): List<Player> = listOf(human, llm)
}
