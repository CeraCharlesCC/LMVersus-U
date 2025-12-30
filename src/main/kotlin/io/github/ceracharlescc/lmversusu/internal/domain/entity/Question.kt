package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Difficulty
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec
import kotlinx.serialization.Serializable
import java.time.Duration
import kotlin.uuid.Uuid

internal data class Question(
    val questionId: Uuid,
    val prompt: String,
    val choices: List<String>? = null,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val verifierSpec: VerifierSpec,
    val metadata: QuestionMetadata? = null,
    val roundTime: Duration? = null,
)

@Serializable
data class QuestionMetadata(
    val category: String? = null,
    val source: String? = null,
    val tags: List<String> = emptyList()
)
