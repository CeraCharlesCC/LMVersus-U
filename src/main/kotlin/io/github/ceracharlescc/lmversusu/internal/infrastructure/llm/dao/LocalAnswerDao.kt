package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao

import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Loads replay items from a dataset (e.g., `items.jsonl` with reasoning + final answer)
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
        // TODO: Implement local replay streaming
        // Expected format: items.jsonl with reasoning + final answer
        // Split reasoning into chunks, emit as ReasoningDelta with approximate token counts
        TODO("LocalAnswerDao.streamReplay not yet implemented")
    }

    /**
     * Gets the complete replayed answer for the given question.
     *
     * @param questionId The UUID of the question to replay
     * @return The complete [LlmAnswer] from the replay dataset
     * @throws IllegalArgumentException if the questionId is not found in the dataset
     */
    suspend fun getReplayAnswer(questionId: Uuid): LlmAnswer {
        // TODO: Implement local replay answer lookup
        TODO("LocalAnswerDao.getReplayAnswer not yet implemented")
    }
}
