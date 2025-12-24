package io.github.ceracharlescc.lmversusu.internal.utils

import java.time.Duration
import kotlin.math.roundToLong

internal fun Duration.scaledBy(multiplier: Double): Duration {
    require(multiplier >= 0.0) { "multiplier must be non-negative" }
    if (isZero) return this

    val baseMillis = toMillis()
    val scaledMillis = (baseMillis.toDouble() * multiplier).roundToLong()
    return Duration.ofMillis(scaledMillis)
}