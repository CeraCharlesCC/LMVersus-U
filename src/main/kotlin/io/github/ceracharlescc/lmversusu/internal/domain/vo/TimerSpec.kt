package io.github.ceracharlescc.lmversusu.internal.domain.vo

import java.time.Duration

internal data class TimerSpec(
    val handicap: Duration,
    val roundDuration: Duration,
    val warningThreshold: Duration? = null
)
