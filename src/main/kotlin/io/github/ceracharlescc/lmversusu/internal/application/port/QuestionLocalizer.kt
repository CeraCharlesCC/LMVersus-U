package io.github.ceracharlescc.lmversusu.internal.application.port

import kotlin.uuid.Uuid

/**
 * Applies UI localization overlay to canonical question content.
 * This is presentation-layer concern only; domain/LLM always use canonical content.
 */
internal interface QuestionLocalizer {
    /**
     * Attempts to localize a question for the given locale.
     *
     * @param locale Normalized locale string (e.g. "ja", "ko", "fr")
     * @param questionId Unique question identifier
     * @param canonicalPrompt The canonical (English) prompt
     * @param canonicalChoices The canonical (English) choices, or null for free-response
     * @return Localized content if available, otherwise canonical content (always safe fallback)
     */
    suspend fun localize(
        locale: String,
        questionId: Uuid,
        canonicalPrompt: String,
        canonicalChoices: List<String>?,
    ): LocalizedQuestion
}

internal data class LocalizedQuestion(
    val prompt: String,
    val choices: List<String>?,
)
