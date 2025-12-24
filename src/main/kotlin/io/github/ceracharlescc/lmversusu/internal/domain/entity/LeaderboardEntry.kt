package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.uuid.Uuid

@Serializable
internal data class LeaderboardEntry(
    val sessionId: UUID,
    val gameMode: GameMode,
    val difficulty: Difficulty,
    val rank: Int,
    val userId: Uuid,
    val nickname: String,
    val bestScore: Double,
    val bestTimeMs: Long,
)

