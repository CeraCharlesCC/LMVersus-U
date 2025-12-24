package io.github.ceracharlescc.lmversusu.internal.domain.vo

internal data class LlmProfile(
    val modelName: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024,
    val displayName: String = modelName
) {
    companion object {
        val DEFAULT = LlmProfile(
            modelName = "gpt-4o-mini",
            temperature = 0.7,
            maxTokens = 1024,
            displayName = "GPT-4o Mini"
        )
    }
}
