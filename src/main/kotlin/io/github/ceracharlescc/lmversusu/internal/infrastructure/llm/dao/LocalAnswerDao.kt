package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.uuid.Uuid

/**
 * Loads replay items from a dataset pack and returns either a full replay answer or a stream
 * of deltas and terminal events.
 *
 * The streaming implementation may split reasoning into chunks and emit them as
 * [LlmStreamEvent.ReasoningDelta] events with approximate token counts (the orchestrator
 * consumes these counts for pacing).
 *
 * @param datasetPath Path to the dataset directory containing manifest + replay files
 */
internal class LocalAnswerDao(
    val datasetPath: String,
) {

    private companion object {
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val REPLAYS_DIRECTORY_NAME = "replays"
        const val REASONING_CHUNK_CHAR_LIMIT = 120
        const val CHARS_PER_TOKEN_ESTIMATE = 4.0
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val datasetDirectory: Path = Paths.get(datasetPath).normalize()
    @Volatile
    private var cachedManifest: LightweightPackManifest? = null

    /**
     * Streams a replayed answer for the given question.
     *
     * Splits the pre-recorded reasoning into chunks and emits them as
     * [LlmStreamEvent.ReasoningDelta] events, followed by a terminal
     * [LlmStreamEvent.FinalAnswer] or [LlmStreamEvent.Error].
     *
     * @param questionId The UUID of the question to replay
     * @return A flow of [LlmStreamEvent]s representing the replayed answer
     */
    fun streamReplay(questionId: Uuid): Flow<LlmStreamEvent> {
        return flow {
            val replay = runCatching { loadReplay(questionId) }.getOrElse { error ->
                emit(
                    LlmStreamEvent.Error(
                        message = error.message ?: "Failed to load replay file",
                        cause = error,
                    )
                )
                return@flow
            }

            if (replay == null) {
                emit(
                    LlmStreamEvent.Error(
                        message = "QuestionId $questionId not found in dataset $datasetDirectory"
                    )
                )
                return@flow
            }

            val reasoning = replay.llmReasoning.orEmpty()
            val totalTokenCount = estimateTokenCount(reasoning)

            for (chunk in chunkReasoning(reasoning)) {
                val emittedTokenCount = estimateTokenCount(chunk)
                emit(
                    LlmStreamEvent.ReasoningDelta(
                        deltaText = chunk,
                        emittedTokenCount = emittedTokenCount,
                        totalTokenCount = totalTokenCount,
                    )
                )
            }

            emit(LlmStreamEvent.FinalAnswer(buildAnswer(replay)))
        }
    }

    /**
     * Gets the complete replayed answer for the given question.
     *
     * @param questionId The UUID of the question to replay
     * @return The complete [LlmAnswer] from the replay dataset
     * @throws IllegalArgumentException if the questionId is not found in the dataset
     */
    suspend fun getReplayAnswer(questionId: Uuid): LlmAnswer {
        val replay = loadReplay(questionId)
            ?: throw IllegalArgumentException("QuestionId $questionId not found in dataset $datasetDirectory")

        return buildAnswer(replay)
    }

    suspend fun availableQuestionIds(): Set<Uuid> {
        val manifest = loadManifest() ?: return emptySet()
        return manifest.availableQuestionIds.mapNotNull { parseUuidOrNull(it) }.toSet()
    }

    suspend fun questionSetPath(): String? {
        return loadManifest()?.questionSetPath
    }

    private fun buildAnswer(replay: ReplayFile): LlmAnswer {
        return LlmAnswer(
            finalAnswer = replay.llmFinalAnswer,
        )
    }

    private suspend fun loadManifest(): LightweightPackManifest? {
        cachedManifest?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val manifestFile = datasetDirectory.resolve(MANIFEST_FILE_NAME)
            if (!Files.isRegularFile(manifestFile)) return@withContext null
            val content = Files.readString(manifestFile)
            runCatching {
                json.decodeFromString(LightweightPackManifest.serializer(), content)
            }.getOrNull()
        }
        if (loaded != null) {
            cachedManifest = loaded
        }
        return loaded
    }

    private suspend fun loadReplay(questionId: Uuid): ReplayFile? {
        return withContext(Dispatchers.IO) {
            val replayFile = datasetDirectory
                .resolve(REPLAYS_DIRECTORY_NAME)
                .resolve("${questionId}.json")
            if (!Files.isRegularFile(replayFile)) return@withContext null
            val content = Files.readString(replayFile)
            runCatching {
                json.decodeFromString(ReplayFile.serializer(), content)
            }.getOrNull()
        }
    }

    private fun chunkReasoning(reasoning: String): Sequence<String> {
        if (reasoning.isEmpty()) return emptySequence()

        return sequence {
            var index = 0
            while (index < reasoning.length) {
                val end = (index + REASONING_CHUNK_CHAR_LIMIT).coerceAtMost(reasoning.length)
                yield(reasoning.substring(index, end))
                index = end
            }
        }
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isEmpty()) return 0
        return ceil(text.length / CHARS_PER_TOKEN_ESTIMATE).toInt().coerceAtLeast(1)
    }

    @Serializable
    private data class LightweightPackManifest(
        val packId: String,
        val version: Int,
        val questionSetPath: String? = null,
        val availableQuestionIds: List<String> = emptyList(),
        val llmProfile: LlmProfile? = null
    )

    @Serializable
    private data class ReplayFile(
        val questionId: String,
        val llmReasoning: String? = null,
        val llmFinalAnswer: Answer,
        val embeddedVerifierHint: VerifierSpec? = null,
        val replay: ReplayMetadata? = null
    )

    @Serializable
    private data class ReplayMetadata(
        val reasoningTokenCount: Int? = null,
        val avgTokensPerSecond: Int? = null
    )

    private fun parseUuidOrNull(raw: String): Uuid? {
        return runCatching { Uuid.parse(raw) }.getOrNull()
    }
}
