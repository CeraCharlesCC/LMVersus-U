package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository

import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionBank
import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionConstraints
import io.github.ceracharlescc.lmversusu.internal.di.annotation.ConfigDirectory
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.entity.QuestionMetadata
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.uuid.Uuid

@Singleton
internal class FileQuestionBankImpl @Inject constructor(
    @param:ConfigDirectory private val configDirectory: Path,
    private val logger: Logger,
) : QuestionBank {

    private companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val QUESTIONS_DIRECTORY_NAME = "questions"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val cacheByResolvedSetDir = ConcurrentHashMap<String, QuestionSetCache>()
    private val inflightLoads = ConcurrentHashMap<String, CompletableDeferred<QuestionSetCache?>>()

    override suspend fun pickQuestions(
        questionSetPath: String,
        count: Int,
        constraints: QuestionConstraints,
    ): List<Question> {
        if (count <= 0) return emptyList()

        val setDir = resolveDirectory(questionSetPath)
        val cache = loadOrGetCached(setDir) ?: return emptyList()

        val candidates = cache.questions.values
            .asSequence()
            .filter { it.questionId !in constraints.excludeQuestionIds }
            .filter { constraints.allowedQuestionIds == null || it.questionId in constraints.allowedQuestionIds }
            .filter { constraints.difficulty == null || it.difficulty == constraints.difficulty }
            .filter { matchesCategory(it, constraints.categories) }
            .toList()

        if (candidates.isEmpty()) return emptyList()
        return candidates.shuffled(Random.Default).take(count)
    }

    private fun matchesCategory(question: Question, categories: List<String>?): Boolean {
        if (categories.isNullOrEmpty()) return true
        val category = question.metadata?.category ?: return false
        return category in categories
    }

    private suspend fun loadOrGetCached(questionSetDir: Path): QuestionSetCache? {
        val key = questionSetDir.normalize().toString()

        cacheByResolvedSetDir[key]?.let { return it }

        val myDeferred = CompletableDeferred<QuestionSetCache?>()
        val existingDeferred = inflightLoads.putIfAbsent(key, myDeferred)

        if (existingDeferred != null) {
            return runCatching { existingDeferred.await() }.getOrNull()
        }

        return try {
            val loaded = loadQuestionSet(questionSetDir)

            if (loaded != null) {
                cacheByResolvedSetDir[key] = loaded
            }

            myDeferred.complete(loaded)
            loaded
        } catch (t: Throwable) {
            myDeferred.completeExceptionally(t)
            null
        } finally {
            inflightLoads.remove(key, myDeferred)
        }
    }

    private suspend fun loadQuestionSet(questionSetDir: Path): QuestionSetCache? = withContext(Dispatchers.IO) {
        val manifestPath = questionSetDir.resolve(MANIFEST_FILE_NAME)
        if (!Files.isRegularFile(manifestPath)) {
            logger.warn("Question set manifest not found at {}", manifestPath)
            return@withContext null
        }

        val manifest = runCatching {
            json.decodeFromString(QuestionSetManifest.serializer(), Files.readString(manifestPath))
        }.getOrElse {
            logger.warn("Failed to parse question set manifest at {}: {}", manifestPath, it.message)
            return@withContext null
        }

        val distinctIds = manifest.questionIds.distinct()
        if (distinctIds.size != manifest.questionIds.size) {
            logger.warn(
                "Question set manifest at {} contains duplicate questionIds ({} total, {} distinct)",
                manifestPath,
                manifest.questionIds.size,
                distinctIds.size,
            )
        }

        val questionsDir = questionSetDir.resolve(QUESTIONS_DIRECTORY_NAME)
        val questions = distinctIds
            .asSequence()
            .mapNotNull { rawId ->
                val parsed = parseUuidOrNull(rawId) ?: return@mapNotNull null
                loadQuestionFile(questionsDir, rawId)?.toDomainQuestion(parsed)
            }
            .associateBy { it.questionId }

        if (questions.isEmpty()) {
            logger.warn("No questions loaded from question set at {}", questionSetDir)
            return@withContext null
        }

        QuestionSetCache(
            setId = manifest.setId,
            version = manifest.version,
            questions = questions,
        )
    }

    private fun loadQuestionFile(questionsDir: Path, questionId: String): QuestionFile? {
        val questionPath = questionsDir.resolve("$questionId.json")
        if (!Files.isRegularFile(questionPath)) {
            logger.warn("Question file missing: {}", questionPath)
            return null
        }

        return runCatching {
            json.decodeFromString(QuestionFile.serializer(), Files.readString(questionPath))
        }.getOrNull()
    }

    private fun QuestionFile.toDomainQuestion(questionId: Uuid): Question {
        val roundTimeDuration = roundTimeSeconds
            ?.takeIf { it > 0 }
            ?.let { Duration.ofSeconds(it) }

        return Question(
            questionId = questionId,
            prompt = prompt,
            choices = choices,
            difficulty = difficulty,
            verifierSpec = verifierSpec,
            metadata = metadata,
            roundTime = roundTimeDuration,
        )
    }

    private fun resolveDirectory(rawPath: String): Path {
        val path = Paths.get(rawPath)
        return (if (path.isAbsolute) path else configDirectory.resolve(path)).normalize()
    }

    private fun parseUuidOrNull(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

    private data class QuestionSetCache(
        val setId: String,
        val version: Int,
        val questions: Map<Uuid, Question>,
    )

    @Serializable
    private data class QuestionSetManifest(
        val setId: String,
        val version: Int,
        val questionIds: List<String>,
    )

    @Serializable
    private data class QuestionFile(
        val questionId: String,
        val prompt: String,
        val choices: List<String>? = null,
        val difficulty: Difficulty = Difficulty.MEDIUM,
        val verifierSpec: VerifierSpec,
        val metadata: QuestionMetadata? = null,
        @SerialName("roundTime")
        val roundTimeSeconds: Long? = null,
    )
}