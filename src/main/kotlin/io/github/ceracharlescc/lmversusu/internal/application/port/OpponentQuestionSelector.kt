package io.github.ceracharlescc.lmversusu.internal.application.port

import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionBank
import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionConstraints
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.repository.LightweightDatasetRepository
import org.slf4j.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class OpponentQuestionSelector @Inject constructor(
    private val questionBank: QuestionBank,
    private val lightweightDatasetRepository: LightweightDatasetRepository,
    private val logger: Logger,
) {

    suspend fun pickQuestionsForOpponent(
        opponentSpec: OpponentSpec,
        count: Int,
        constraints: QuestionConstraints = QuestionConstraints(),
    ): List<Question> {
        val allowedByOpponent = when (opponentSpec) {
            is OpponentSpec.Premium -> null
            is OpponentSpec.Lightweight -> lightweightDatasetRepository.availableQuestionIds(opponentSpec.datasetPath)
        }

        val mergedConstraints = constraints.copy(
            allowedQuestionIds = intersectNullable(constraints.allowedQuestionIds, allowedByOpponent),
        )

        val questionSetPath = when (opponentSpec) {
            is OpponentSpec.Premium -> opponentSpec.questionSetPath
            is OpponentSpec.Lightweight -> opponentSpec.questionSetPath
        }

        if (opponentSpec is OpponentSpec.Lightweight) {
            val declared = lightweightDatasetRepository.declaredQuestionSetPath(opponentSpec.datasetPath)
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

    private fun intersectNullable(a: Set<Uuid>?, b: Set<Uuid>?): Set<Uuid>? {
        return when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            else -> a intersect b
        }
    }
}