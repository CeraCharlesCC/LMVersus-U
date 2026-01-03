package io.github.ceracharlescc.lmversusu.internal.infrastructure.i18n

import io.github.ceracharlescc.lmversusu.internal.application.port.LocalizedQuestion
import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionLocalizer
import io.github.ceracharlescc.lmversusu.internal.di.annotation.ConfigDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.uuid.Uuid

/**
 * Loads question translations from JSON files in Datasets/i18n/<locale>/<questionId>.json
 * 
 * File schema:
 * {
 *   "prompt": "Localized prompt text",
 *   "choices": ["Choice A", "Choice B", "Choice C", "Choice D"]
 * }
 * 
 * Features:
 * - In-memory caching (both hits and misses) to avoid repeated disk I/O
 * - Safe fallback to canonical content on any error
 * - Validates choice count matches canonical
 * - Thread-safe cache using ConcurrentHashMap
 */
@Singleton
internal class FileQuestionLocalizerImpl @Inject constructor(
    @ConfigDirectory configDir: Path,
    private val logger: Logger,
) : QuestionLocalizer {
    private val i18nBasePath: Path = configDir / "LLM-Configs" / "Datasets" / "i18n"

    private val cache = ConcurrentHashMap<CacheKey, Optional<TranslationFile>>()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun localize(
        locale: String,
        questionId: Uuid,
        canonicalPrompt: String,
        canonicalChoices: List<String>?,
    ): LocalizedQuestion {
        val normalized = normalizeLocale(locale)
        if (normalized == null || normalized == "en") {
            return LocalizedQuestion(canonicalPrompt, canonicalChoices)
        }

        val key = CacheKey(normalized, questionId)

        val cached = cache[key]

        logger.debug("localize: cache hit='{}' for locale='{}' qid='{}'", (cached != null), normalized, questionId)
        val translation = if (cached != null) {
            cached.orElse(null)
        } else {
            val loaded = loadTranslationFile(normalized, questionId)
            val wrapped = Optional.ofNullable(loaded)
            // putIfAbsent returns null if the key was absent (our value was inserted),
            // or returns the existing value if another thread beat us to it
            val existing = cache.putIfAbsent(key, wrapped)
            existing?.orElse(null) ?: loaded
        }

        return applyTranslation(translation, canonicalPrompt, canonicalChoices)
    }

    private suspend fun loadTranslationFile(locale: String, questionId: Uuid): TranslationFile? =
        withContext(Dispatchers.IO) {
            val filePath = (i18nBasePath / locale / "$questionId.json")
            try {
                if (!filePath.isRegularFile()) {
                    logger.warn(
                        "translation file not found: '{}' (base='{}')",
                        filePath.absolutePathString(),
                        i18nBasePath.absolutePathString(),
                    )
                    return@withContext null
                }

                logger.info("loading translation file: '{}'", filePath.absolutePathString())

                val content = filePath.readText(Charsets.UTF_8) // single read, correct charset
                json.decodeFromString<TranslationFile>(content)
            } catch (e: Exception) {
                logger.warn(
                    "Failed to load translation for locale='{}', questionId='{}' from path='{}'",
                    locale,
                    questionId,
                    filePath.absolutePathString(),
                    e
                )
                null
            }
        }

    private fun applyTranslation(
        translation: TranslationFile?,
        canonicalPrompt: String,
        canonicalChoices: List<String>?,
    ): LocalizedQuestion {
        if (translation == null) {
            return LocalizedQuestion(canonicalPrompt, canonicalChoices)
        }

        val localizedPrompt = translation.prompt ?: canonicalPrompt
        val localizedChoices = when {
            translation.choices == null -> canonicalChoices
            canonicalChoices == null -> canonicalChoices // Free response, ignore translation
            translation.choices.size != canonicalChoices.size -> canonicalChoices // Mismatch, fallback
            else -> translation.choices
        }

        return LocalizedQuestion(localizedPrompt, localizedChoices)
    }

    private fun normalizeLocale(locale: String): String? {
        if (locale.isBlank()) return null

        // Extract primary language subtag (ja from ja-JP, pt from pt-BR)
        val normalized = locale.lowercase()
            .split('-', '_')
            .firstOrNull()
            ?.filter { it.isLetterOrDigit() }
            ?.take(5)

        return if (!normalized.isNullOrBlank()) normalized else null
    }

    private data class CacheKey(val locale: String, val questionId: Uuid)
}

@Serializable
internal data class TranslationFile(
    val prompt: String? = null,
    val choices: List<String>? = null,
)
