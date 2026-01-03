package io.github.ceracharlescc.lmversusu.internal.domain.policy

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import io.github.ceracharlescc.lmversusu.internal.utils.scaledBy
import java.time.Duration

internal object HandicapPolicy {
    private val LIGHTWEIGHT_BASE_HANDICAP = Duration.ofSeconds(1)
    private val PREMIUM_BASE_HANDICAP = Duration.ofSeconds(1)

    private val DIFFICULTY_MULTIPLIERS = mapOf(
        Difficulty.VERY_EASY to 10.0,
        Difficulty.EASY to 17.5,
        Difficulty.MEDIUM to 25.0,
        Difficulty.HARD to 37.0,
        Difficulty.VERY_HARD to 45.0
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
        return baseHandicap.scaledBy(multiplier)
    }
}
