package io.github.ceracharlescc.lmversusu.internal.domain.entity

import java.time.Instant
import java.util.UUID

internal data class SessionResult(
    val sessionId: UUID,
    val mode: GameMode,
    val llmProfileName: String,
    val humanNickname: String,
    val humanUserId: String,
    val humanScore: Double,
    val llmScore: Double,
    val humanWon: Boolean,
    val durationMs: Long,
    val completedAt: Instant
)