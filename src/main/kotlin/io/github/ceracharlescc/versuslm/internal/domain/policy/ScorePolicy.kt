package io.github.ceracharlescc.lmversusu.internal.domain.policy

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.RoundResult
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Score
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Winner
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Round
import io.github.ceracharlescc.lmversusu.internal.domain.vo.PlayerOutcome
import io.github.ceracharlescc.lmversusu.internal.domain.vo.ScoreBreakdown
import java.time.Duration

internal object ScorePolicy {
    private const val CORRECT_ANSWER_POINTS = 100.0
    private const val INCORRECT_ANSWER_POINTS = 0.0
    private const val MAX_SPEED_BONUS = 50.0

    /**
     * Computes the result for a round once both submissions are in.
     * 
     * @param round The round with both human and LLM submissions
     * @param correctAnswer The correct answer for verification
     * @param humanCorrect Whether the human answered correctly
     * @param llmCorrect Whether the LLM answered correctly
     * @return The computed round result
     */
    fun compute(
        round: Round,
        correctAnswer: Answer,
        humanCorrect: Boolean,
        llmCorrect: Boolean
    ): RoundResult {
        val humanSubmission = round.humanSubmission
        val llmSubmission = round.llmSubmission

        val humanTime = humanSubmission?.responseTimeFrom(round.releasedAt) ?: Duration.ZERO
        val llmTime = llmSubmission?.responseTimeFrom(round.releasedAt) ?: Duration.ZERO

        val humanScore = calculateScore(humanCorrect, humanTime, round.handicap)
        val llmScore = calculateScore(llmCorrect, llmTime, round.handicap)

        val humanOutcome = PlayerOutcome(
            correct = humanCorrect,
            responseTime = humanTime,
            score = humanScore
        )

        val llmOutcome = PlayerOutcome(
            correct = llmCorrect,
            responseTime = llmTime,
            score = llmScore
        )

        val winner = determineWinner(humanCorrect, llmCorrect, humanTime, llmTime)

        return RoundResult(
            correctAnswer = correctAnswer,
            humanOutcome = humanOutcome,
            llmOutcome = llmOutcome,
            winner = winner
        )
    }

    private fun calculateScore(
        correct: Boolean,
        responseTime: Duration,
        handicap: Duration
    ): Score {
        val correctnessPoints = if (correct) CORRECT_ANSWER_POINTS else INCORRECT_ANSWER_POINTS

        // Speed bonus: faster response = more bonus, capped at MAX_SPEED_BONUS
        // Only award speed bonus for correct answers
        val speedBonus = if (correct) {
            val maxTimeMs = handicap.toMillis().coerceAtLeast(30_000)
            val responseFraction = 1.0 - (responseTime.toMillis().toDouble() / maxTimeMs).coerceIn(0.0, 1.0)
            MAX_SPEED_BONUS * responseFraction
        } else {
            0.0
        }

        val breakdown = ScoreBreakdown(
            correctnessPoints = correctnessPoints,
            speedBonus = speedBonus
        )

        return Score(
            points = breakdown.total,
            breakdown = breakdown
        )
    }

    private fun determineWinner(
        humanCorrect: Boolean,
        llmCorrect: Boolean,
        humanTime: Duration,
        llmTime: Duration
    ): Winner {
        return when {
            humanCorrect && !llmCorrect -> Winner.HUMAN
            !humanCorrect && llmCorrect -> Winner.LLM
            humanCorrect && llmCorrect -> {
                // Both correct: faster wins
                when {
                    humanTime < llmTime -> Winner.HUMAN
                    llmTime < humanTime -> Winner.LLM
                    else -> Winner.TIE
                }
            }

            else -> {
                // Both wrong: no winner
                Winner.NONE
            }
        }
    }
}
