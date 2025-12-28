package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import kotlin.uuid.Uuid

internal interface QuestionBank {

    /**
     * Picks questions from a specific question set directory.
     *
     * @param questionSetPath directory that contains manifest.json + questions/
     */
    suspend fun pickQuestions(
        questionSetPath: String,
        count: Int,
        constraints: QuestionConstraints = QuestionConstraints(),
    ): List<Question>
}

internal data class QuestionConstraints(
    val difficulty: Difficulty? = null,
    val categories: List<String>? = null,
    val excludeQuestionIds: Set<Uuid> = emptySet(),
    val allowedQuestionIds: Set<Uuid>? = null
)
