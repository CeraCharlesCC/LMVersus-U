package io.github.ceracharlescc.lmversusu.internal.domain.policy

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object HandicapPolicy {
    private val LIGHTWEIGHT_BASE_HANDICAP = 10.seconds
    private val PREMIUM_BASE_HANDICAP = 15.seconds

    private val DIFFICULTY_MULTIPLIERS = mapOf(
        Question.Difficulty.EASY to 0.8,
        Question.Difficulty.MEDIUM to 1.0,
        Question.Difficulty.HARD to 1.2
    )

    /**
     * Computes the handicap duration for a given question and game mode.
     *
     * @param question The question for the round
     * @param mode The current game mode
     * @return The handicap duration (human head start before LLM can answer)
     */
    fun computeHandicap(
        question: Question,
        mode: GameMode
    ): Duration {
        val baseHandicap = when (mode) {
            GameMode.LIGHTWEIGHT -> LIGHTWEIGHT_BASE_HANDICAP
            GameMode.PREMIUM -> PREMIUM_BASE_HANDICAP
        }

        val multiplier = DIFFICULTY_MULTIPLIERS[question.difficulty] ?: 1.0
        return baseHandicap * multiplier
    }
}
