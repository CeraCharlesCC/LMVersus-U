package io.github.ceracharlescc.lmversusu.internal.domain.entity

import kotlinx.serialization.Serializable

@Serializable
internal data class LeaderboardEntry(
    val rank: Int,
    val userId: String,
    val nickname: String,
    val bestScore: Double,
    val bestTimeMs: Long,
    val gamesPlayed: Int
)

