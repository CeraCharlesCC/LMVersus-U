package io.github.ceracharlescc.versuslm.internal.application.port

import io.github.ceracharlescc.versuslm.internal.domain.entity.Question
import io.github.ceracharlescc.versuslm.internal.domain.entity.Submission
import io.github.ceracharlescc.versuslm.internal.domain.vo.VerificationOutcome

internal interface AnswerVerifier {
    /**
     * Verifies a submission against a question.
     *
     * @param question The question being answered
     * @param submission The submitted answer
     * @return The verification outcome
     */
    fun verify(
        question: Question,
        submission: Submission
    ): VerificationOutcome
}
