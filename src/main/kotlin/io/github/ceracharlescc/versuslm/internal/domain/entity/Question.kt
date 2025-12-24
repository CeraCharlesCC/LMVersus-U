package io.github.ceracharlescc.versuslm.internal.domain.entity

import io.github.ceracharlescc.versuslm.internal.domain.vo.VerifierSpec
import java.util.UUID

internal data class Question(
    val questionId: UUID,
    val prompt: String,
    val choices: List<String>? = null,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val verifierSpec: VerifierSpec,
    val metadata: QuestionMetadata? = null
) {
    enum class Difficulty {
        EASY,
        MEDIUM,
        HARD
    }
}

data class QuestionMetadata(
    val category: String? = null,
    val source: String? = null,
    val tags: List<String> = emptyList()
)
