package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import org.slf4j.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class OpponentQuestionSelector @Inject constructor(
    private val questionBank: QuestionBank,
    private val llmPlayerGateway: LlmPlayerGateway,
    private val logger: Logger,
) {

    suspend fun pickQuestionsForOpponent(
        opponentSpec: OpponentSpec,
        count: Int,
        constraints: QuestionConstraints = QuestionConstraints(),
    ): List<Question> {
        val allowedByOpponent = llmPlayerGateway.availableQuestionIds(opponentSpec)

        val mergedConstraints = constraints.copy(
            allowedQuestionIds = intersectNullable(constraints.allowedQuestionIds, allowedByOpponent),
        )

        val questionSetPath = when (opponentSpec) {
            is OpponentSpec.Premium -> opponentSpec.questionSetPath
            is OpponentSpec.Lightweight -> opponentSpec.questionSetPath
        }

        if (opponentSpec is OpponentSpec.Lightweight) {
            val declared = llmPlayerGateway.declaredQuestionSetPath(opponentSpec)
            if (!declared.isNullOrBlank() && declared != opponentSpec.questionSetPath) {
                logger.warn(
                    "Lightweight specId={} questionSetPath mismatch: spec={}, datasetManifest={}",
                    opponentSpec.id,
                    opponentSpec.questionSetPath,
                    declared,
                )
            }
        }

        return questionBank.pickQuestions(
            questionSetPath = questionSetPath,
            count = count,
            constraints = mergedConstraints,
        )
    }

    private fun intersectNullable(a: Set<Uuid>?, b: Set<Uuid>?): Set<Uuid>? =
        when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            else -> a intersect b
        }
}