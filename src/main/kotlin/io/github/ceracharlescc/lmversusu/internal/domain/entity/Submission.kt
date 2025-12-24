package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

internal data class Submission(
    val submissionId: Uuid,
    val playerId: Uuid,
    val answer: Answer,
    val serverReceivedAt: Instant,
    val clientSentAt: Instant? = null
) {
    fun responseTimeFrom(roundReleasedAt: Instant): Duration {
        return Duration.between(roundReleasedAt, serverReceivedAt)
    }
}
