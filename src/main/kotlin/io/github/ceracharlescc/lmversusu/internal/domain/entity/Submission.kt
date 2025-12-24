package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class Submission(
    val submissionId: UUID,
    val playerId: UUID,
    val answer: Answer,
    val serverReceivedAt: Instant,
    val clientSentAt: Instant? = null
) {
    fun responseTimeFrom(roundReleasedAt: Instant): Duration {
        return Duration.between(roundReleasedAt, serverReceivedAt)
    }
}
