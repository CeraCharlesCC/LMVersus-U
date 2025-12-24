package io.github.ceracharlescc.versuslm.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import java.time.Instant
import java.util.UUID

internal data class SessionResult(
    val sessionId: UUID,
    val mode: GameMode,
    val llmProfileName: String,
    val humanNickname: String,
    val humanScore: Double,
    val llmScore: Double,
    val humanWon: Boolean,
    val durationMs: Long,
    val completedAt: Instant
)