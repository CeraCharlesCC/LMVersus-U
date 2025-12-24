package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.RoundResult
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class Round(
    val roundId: UUID,
    val question: Question,
    val releasedAt: Instant,
    val handicap: Duration,
    val deadline: Instant,
    val humanSubmission: Submission? = null,
    val llmSubmission: Submission? = null,
    val result: RoundResult? = null
) {
    val isInProgress: Boolean
        get() = result == null

    val hasAllSubmissions: Boolean
        get() = humanSubmission != null && llmSubmission != null
}
