package io.github.ceracharlescc.lmversusu.internal.domain.vo

internal data class Score(
    val points: Double,
    val breakdown: ScoreBreakdown? = null
)

internal data class ScoreBreakdown(
    val correctnessPoints: Double,
    val speedBonus: Double,
    val penalty: Double = 0.0
) {
    val total: Double
        get() = correctnessPoints + speedBonus - penalty
}
