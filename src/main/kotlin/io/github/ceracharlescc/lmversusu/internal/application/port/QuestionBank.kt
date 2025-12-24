package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import kotlin.uuid.Uuid

internal interface QuestionBank {
    /**
     * Picks questions for a game session based on constraints.
     *
     * @param count Number of questions to pick
     * @param constraints Optional constraints for question selection
     * @return List of selected questions
     */
    fun pickQuestions(
        count: Int,
        constraints: QuestionConstraints = QuestionConstraints()
    ): List<Question>
}

internal data class QuestionConstraints(
    val difficulty: Difficulty? = null,
    val categories: List<String>? = null,
    val excludeQuestionIds: Set<Uuid> = emptySet()
)
