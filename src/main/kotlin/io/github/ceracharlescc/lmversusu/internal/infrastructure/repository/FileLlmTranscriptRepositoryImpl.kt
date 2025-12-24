package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository


import io.github.ceracharlescc.lmversusu.internal.domain.repository.LlmTranscript
import io.github.ceracharlescc.lmversusu.internal.domain.repository.LlmTranscriptRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    private val mutex = Mutex()

    @Volatile
    private var cache: Map<Key, LlmTranscript>? = null

    override suspend fun find(questionId: Uuid, llmProfile: LlmProfile): LlmTranscript? {
        val loaded = loadCacheIfNeeded()

        return loaded[Key(questionId, llmProfile.transcriptKey)]
    }

    private suspend fun loadCacheIfNeeded(): Map<Key, LlmTranscript> {
        cache?.let { return it }

        return mutex.withLock {
            cache?.let { return@withLock it }

            val loaded = withContext(Dispatchers.IO) {
                val path = transcriptFileResolver.resolve()
                if (!Files.exists(path)) {
                    return@withContext emptyMap()
                }

                val text = Files.readString(path)
                val decoded = json.decodeFromString(TranscriptFile.serializer(), text)

                decoded.items.associate { item ->
                    val key = Key(Uuid.parse(item.questionId), item.profileName)
                    key to item.toDomain()
                }
            }

            cache = loaded
            loaded
        }
    }

    private data class Key(val questionId: Uuid, val profileName: String)

    @Serializable
    private data class TranscriptFile(val items: List<TranscriptItem>)

    @Serializable
    private data class TranscriptItem(
        val questionId: String,
        val profileName: String,
        val reasoning: String,
        val finalAnswer: Answer,
        val averageTokensPerSecond: Double,
        val chunkSizeTokens: Int
    ) {
        fun toDomain(): LlmTranscript {
            return LlmTranscript(
                reasoning = reasoning,
                finalAnswer = finalAnswer,
                averageTokensPerSecond = averageTokensPerSecond,
                chunkSizeTokens = chunkSizeTokens
            )
        }
    }
}

@Singleton
internal class TranscriptFileResolver @Inject constructor(
    private val configDir: ConfigDirectory
) {
    fun resolve(): Path {
        return configDir.path
            .resolve("LLM-Configs")
            .resolve("lightweight_quiz.json")
            .toAbsolutePath()
            .normalize()
    }
}

@JvmInline
internal value class ConfigDirectory(val path: Path)