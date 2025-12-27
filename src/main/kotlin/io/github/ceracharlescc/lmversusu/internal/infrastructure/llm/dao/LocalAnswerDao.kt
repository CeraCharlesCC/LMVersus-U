package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.uuid.Uuid

/**
 * Loads replay items from a dataset (`items.jsonl` with reasoning + final answer)
 * and returns either a full replay answer or a stream of deltas and terminal events.
 *
 * The streaming implementation may split reasoning into chunks and emit them as
 * [LlmStreamEvent.ReasoningDelta] events with approximate token counts (the orchestrator
 * consumes these counts for pacing).
 *
 * @param datasetPath Path to the dataset directory containing replay data
 */
internal class LocalAnswerDao(
    val datasetPath: String,
) {

    private companion object {
        const val ITEMS_FILE_NAME = "items.jsonl"
        const val REASONING_CHUNK_CHAR_LIMIT = 120
        const val CHARS_PER_TOKEN_ESTIMATE = 4.0
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val datasetDirectory: Path = Paths.get(datasetPath).normalize()

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
            val item = runCatching { loadItem(questionId) }.getOrElse { error ->
                emit(
                    LlmStreamEvent.Error(
                        message = error.message ?: "Failed to load replay item",
                        cause = error,
                    )
                )
                return@flow
            }

            if (item == null) {
                emit(
                    LlmStreamEvent.Error(
                        message = "QuestionId $questionId not found in dataset $datasetDirectory"
                    )
                )
                return@flow
            }

            val reasoning = item.llmReasoning.orEmpty()
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

            emit(LlmStreamEvent.FinalAnswer(buildAnswer(item)))
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
        val item = loadItem(questionId)
            ?: throw IllegalArgumentException("QuestionId $questionId not found in dataset $datasetDirectory")

        return buildAnswer(item)
    }

    private fun buildAnswer(item: LocalReplayItem): LlmAnswer {
        return LlmAnswer(
            finalAnswer = item.llmFinalAnswer,
        )
    }

    private fun loadItem(questionId: Uuid): LocalReplayItem? {
        val itemsFile = datasetDirectory.resolve(ITEMS_FILE_NAME)
        if (!Files.isRegularFile(itemsFile)) return null

        val targetId = questionId.toString()

        Files.newBufferedReader(itemsFile).use { reader ->
            for (line in reader.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val item = decodeLine(trimmed) ?: continue
                if (item.questionId == targetId) return item
            }
        }

        return null
    }

    private fun decodeLine(line: String): LocalReplayItem? {
        return runCatching {
            json.decodeFromString(LocalReplayItem.serializer(), line)
        }.getOrNull()
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
    private data class LocalReplayItem(
        val questionId: String,
        val llmReasoning: String? = null,
        val llmFinalAnswer: Answer,
    )
}
