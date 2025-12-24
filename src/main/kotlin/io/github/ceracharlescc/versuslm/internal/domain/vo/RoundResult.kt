package io.github.ceracharlescc.lmversusu.internal.domain.vo

import java.time.Duration

internal data class RoundResult(
    val correctAnswer: Answer,
    val humanOutcome: PlayerOutcome,
    val llmOutcome: PlayerOutcome,
    val winner: Winner
)

internal data class PlayerOutcome(
    val correct: Boolean,
    val responseTime: Duration,
    val score: Score
)

internal enum class Winner {
    HUMAN,
    LLM,
    TIE,
    NONE
}
