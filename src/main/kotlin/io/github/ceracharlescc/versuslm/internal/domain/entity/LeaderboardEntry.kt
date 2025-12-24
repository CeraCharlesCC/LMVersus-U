package io.github.ceracharlescc.versuslm.internal.domain.entity

import kotlinx.serialization.Serializable

@Serializable
internal data class LeaderboardEntry(
    val rank: Int,
    val nickname: String,
    val bestScore: Double,
    val bestTimeMs: Long,
    val gamesPlayed: Int
)

