package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.response

import io.github.ceracharlescc.lmversusu.internal.domain.entity.LeaderboardEntry
import kotlinx.serialization.Serializable

@Serializable
internal data class LeaderboardResponse(
    val entries: List<LeaderboardEntry>,
    val total: Int,
    val limit: Int
)
