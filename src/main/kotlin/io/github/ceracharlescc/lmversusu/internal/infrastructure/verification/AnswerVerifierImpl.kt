package io.github.ceracharlescc.lmversusu.internal.infrastructure.verification

import io.github.ceracharlescc.lmversusu.internal.application.port.AnswerVerifier
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Submission
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerificationOutcome
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AnswerVerifierImpl @Inject constructor() : AnswerVerifier {
    override fun verify(question: Question, submission: Submission): VerificationOutcome {
        val answer = submission.answer
        val correct = when (val spec = question.verifierSpec) {
            is VerifierSpec.MultipleChoice -> {
                val choice = answer as? Answer.MultipleChoice
                choice?.choiceIndex == spec.correctIndex
            }

            is VerifierSpec.IntegerRange -> {
                val integer = answer as? Answer.Integer
                val value = integer?.value
                value != null && value in spec.minValue..spec.maxValue && value == spec.correctValue
            }

            is VerifierSpec.FreeResponse -> {
                val text = (answer as? Answer.FreeText)?.text?.trim().orEmpty()
                if (spec.expectedKeywords.isEmpty()) {
                    text.isNotBlank()
                } else {
                    val normalized = text.lowercase()
                    spec.expectedKeywords.all { keyword -> normalized.contains(keyword.lowercase()) }
                }
            }
        }
        return VerificationOutcome(correct = correct)
    }
}
