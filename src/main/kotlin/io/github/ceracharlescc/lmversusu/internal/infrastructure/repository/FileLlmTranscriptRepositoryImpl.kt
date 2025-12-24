package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository


import io.github.ceracharlescc.lmversusu.internal.domain.repository.LlmTranscript
import io.github.ceracharlescc.lmversusu.internal.domain.repository.LlmTranscriptRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class FileLlmTranscriptRepositoryImpl @Inject constructor(
    private val json: Json,
    private val transcriptFileResolver: TranscriptFileResolver
) : LlmTranscriptRepository {

    private val cache: Map<Key, LlmTranscript> by lazy {
        val path = transcriptFileResolver.resolve()
        require(Files.exists(path)) {
            "Transcript file not found at path: ${path.toAbsolutePath()}"
        }
        val text = Files.readString(path)
        val decoded = json.decodeFromString(TranscriptFile.serializer(), text)

        decoded.items.associate { item ->
            val key = Key(Uuid.parse(item.questionId), item.profileName)
            key to item.toDomain()
        }
    }

    override fun find(questionId: Uuid, llmProfile: LlmProfile): LlmTranscript? {
        return cache[Key(questionId, llmProfile.modelName)] ?: cache[Key(questionId, llmProfile.displayName)]
    }

    private data class Key(val questionId: Uuid, val profileName: String)

    @Serializable
    private data class TranscriptFile(val items: List<TranscriptItem>)

    @Serializable
    private data class TranscriptItem(
        val questionId: String,
        val profileName: String,
        val reasoning: String,
        val finalAnswer: AnswerJson,
        val averageTokensPerSecond: Double,
        val chunkSizeTokens: Int
    ) {
        fun toDomain(): LlmTranscript {
            return LlmTranscript(
                reasoning = reasoning,
                finalAnswer = finalAnswer.toDomain(),
                averageTokensPerSecond = averageTokensPerSecond,
                chunkSizeTokens = chunkSizeTokens
            )
        }

        val questionUuid: Uuid
            get() = Uuid.parse(questionId)
    }

    @Serializable
    private sealed class AnswerJson {
        @Serializable
        @SerialName("multiple_choice")
        data class MultipleChoice(val choiceIndex: Int) : AnswerJson()

        @Serializable
        @SerialName("integer")
        data class Integer(val value: Int) : AnswerJson()

        @Serializable
        @SerialName("free_text")
        data class FreeText(val text: String) : AnswerJson()

        fun toDomain(): Answer {
            return when (this) {
                is MultipleChoice -> Answer.MultipleChoice(choiceIndex)
                is Integer -> Answer.Integer(value)
                is FreeText -> Answer.FreeText(text)
            }
        }
    }
}

@Singleton
internal class TranscriptFileResolver @Inject constructor() {
    fun resolve(): Path {
        val configDir = System.getProperty("lmversusu.configDir") ?: "."
        return Path.of(configDir).resolve("LLM-Configs").resolve("lightweight_quiz.json").toAbsolutePath().normalize()
    }
}