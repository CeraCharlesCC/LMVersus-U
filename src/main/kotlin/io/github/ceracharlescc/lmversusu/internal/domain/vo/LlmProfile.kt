package io.github.ceracharlescc.lmversusu.internal.domain.vo

internal data class LlmProfile(
    val modelName: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024,
    val displayName: String = modelName
) {
    val transcriptKey: String get() = modelName
}
