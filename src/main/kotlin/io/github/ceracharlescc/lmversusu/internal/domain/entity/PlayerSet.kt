package io.github.ceracharlescc.lmversusu.internal.domain.entity

import kotlin.uuid.Uuid

internal data class PlayerSet(
    val human: Player,
    val llm: Player
) {
    init {
        require(human.type == Player.PlayerType.HUMAN) { "Human player must have HUMAN type" }
        require(llm.type == Player.PlayerType.LLM) { "LLM player must have LLM type" }
    }

    fun findById(playerId: Uuid): Player? = when (playerId) {
        human.playerId -> human
        llm.playerId -> llm
        else -> null
    }

    fun toList(): List<Player> = listOf(human, llm)
}
