@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.domain.vo

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import java.time.Instant
import kotlin.uuid.Uuid

internal enum class WebhookFormat {
    MINIMAL,
    DETAILED,
}

internal sealed interface WebhookEvent {
    val sessionId: Uuid
    val mode: GameMode
    val occurredAt: Instant

    data class SessionStarted(
        override val sessionId: Uuid,
        override val mode: GameMode,
        override val occurredAt: Instant,
        val opponentSpecId: String,
        val humanPlayerId: Uuid,
        val humanNickname: String,
        val llmNickname: String,
        val llmProfileName: String,
        val questionSetDisplayName: String,
    ) : WebhookEvent

    data class SessionCompleted(
        override val sessionId: Uuid,
        override val mode: GameMode,
        override val occurredAt: Instant,
        val humanTotalScore: Double,
        val llmTotalScore: Double,
        val winner: String,
        val roundsPlayed: Int,
        val totalRounds: Int,
        val durationMs: Long,
    ) : WebhookEvent
}
