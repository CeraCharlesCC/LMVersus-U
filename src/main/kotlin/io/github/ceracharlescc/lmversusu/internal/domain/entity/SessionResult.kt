package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import java.time.Instant
import kotlin.uuid.Uuid

internal data class SessionResult(
    val sessionId: Uuid,
    val gameMode: GameMode,
    val difficulty: Difficulty,
    val llmProfileName: String,
    val humanNickname: String,
    val humanUserId: Uuid,
    val humanScore: Double,
    val llmScore: Double,
    val humanWon: Boolean,
    val durationMs: Long,
    val completedAt: Instant
)