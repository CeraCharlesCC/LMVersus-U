package io.github.ceracharlescc.lmversusu.internal.domain.vo

import kotlin.uuid.Uuid

internal data class LlmRoundContext(
    val questionId: Uuid,
    val prompt: String,
    val choices: List<String>?,
    val llmProfile: LlmProfile
)