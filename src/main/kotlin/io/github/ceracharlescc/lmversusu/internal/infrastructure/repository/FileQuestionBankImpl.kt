package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository

import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionBank
import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionConstraints
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.entity.QuestionMetadata
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.random.Random
import kotlin.uuid.Uuid

internal class FileQuestionBankImpl(
    private val questionSetPath: String,
    private val random: Random = Random.Default,
) : QuestionBank {

    private companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val QUESTIONS_DIRECTORY_NAME = "questions"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val questionSetDirectory: Path = Paths.get(questionSetPath).normalize()

    override fun pickQuestions(count: Int, constraints: QuestionConstraints): List<Question> {
        val manifest = loadManifest() ?: return emptyList()
        val excludedIds = constraints.excludeQuestionIds.map { it.toString() }.toSet()
        val allowedIds = constraints.allowedQuestionIds?.map { it.toString() }?.toSet()

        val candidates = manifest.questionIds
            .asSequence()
            .filter { it !in excludedIds }
            .filter { allowedIds == null || it in allowedIds }
            .mapNotNull { loadQuestionFile(it)?.toDomainQuestion() }
            .filter { matchesConstraints(it, constraints) }
            .toList()

        if (candidates.isEmpty()) return emptyList()

        return candidates.shuffled(random).take(count)
    }

    private fun loadManifest(): QuestionSetManifest? {
        val manifestFile = questionSetDirectory.resolve(MANIFEST_FILE_NAME)
        if (!Files.isRegularFile(manifestFile)) return null
        return runCatching {
            val content = Files.readString(manifestFile)
            json.decodeFromString(QuestionSetManifest.serializer(), content)
        }.getOrNull()
    }

    private fun loadQuestionFile(questionId: String): QuestionFile? {
        val questionFile = questionSetDirectory
            .resolve(QUESTIONS_DIRECTORY_NAME)
            .resolve("$questionId.json")
        if (!Files.isRegularFile(questionFile)) return null
        return runCatching {
            val content = Files.readString(questionFile)
            json.decodeFromString(QuestionFile.serializer(), content)
        }.getOrNull()
    }

    private fun QuestionFile.toDomainQuestion(): Question? {
        val parsedId = parseUuidOrNull(questionId) ?: return null
        return Question(
            questionId = parsedId,
            prompt = prompt,
            choices = choices,
            difficulty = difficulty,
            verifierSpec = verifierSpec,
            metadata = metadata
        )
    }

    private fun matchesConstraints(question: Question, constraints: QuestionConstraints): Boolean {
        val difficulty = constraints.difficulty
        if (difficulty != null && question.difficulty != difficulty) return false

        val categories = constraints.categories
        if (!categories.isNullOrEmpty()) {
            val category = question.metadata?.category ?: return false
            if (category !in categories) return false
        }

        return true
    }

    private fun parseUuidOrNull(raw: String): Uuid? {
        return runCatching { Uuid.parse(raw) }.getOrNull()
    }

    @Serializable
    private data class QuestionSetManifest(
        val setId: String,
        val version: Int,
        val questionIds: List<String>,
        val metadata: QuestionSetMetadata? = null
    )

    @Serializable
    private data class QuestionSetMetadata(
        val source: String? = null,
        val notes: String? = null
    )

    @Serializable
    private data class QuestionFile(
        val questionId: String,
        val prompt: String,
        val choices: List<String>? = null,
        val difficulty: Difficulty = Difficulty.MEDIUM,
        val verifierSpec: VerifierSpec,
        val metadata: QuestionMetadata? = null
    )
}
