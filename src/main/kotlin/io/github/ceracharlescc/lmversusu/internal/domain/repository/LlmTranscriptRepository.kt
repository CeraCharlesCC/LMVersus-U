package io.github.ceracharlescc.lmversusu.internal.domain.repository

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import kotlin.uuid.Uuid

internal interface LlmTranscriptRepository {
    suspend fun find(
        questionId: Uuid,
        llmProfile: LlmProfile
    ): LlmTranscript?
}

internal data class LlmTranscript(
    val reasoning: String,
    val finalAnswer: Answer,
    val averageTokensPerSecond: Double,
    val chunkSizeTokens: Int
)