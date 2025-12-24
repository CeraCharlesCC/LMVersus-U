package io.github.ceracharlescc.versuslm.internal.domain.vo

import kotlin.time.Duration

internal data class TimerSpec(
    val handicap: Duration,
    val roundDuration: Duration,
    val warningThreshold: Duration? = null
)
