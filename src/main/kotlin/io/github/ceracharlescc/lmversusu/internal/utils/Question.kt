package io.github.ceracharlescc.lmversusu.internal.utils

import io.github.ceracharlescc.lmversusu.internal.application.port.ExpectedAnswerKind
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec

internal fun Question.expectedAnswerKind(): ExpectedAnswerKind = when {
    choices != null -> ExpectedAnswerKind.MULTIPLE_CHOICE
    verifierSpec is VerifierSpec.IntegerRange -> ExpectedAnswerKind.INTEGER
    else -> ExpectedAnswerKind.FREE_TEXT
}

internal fun Question.expectedAnswerTypeString(): String = when (expectedAnswerKind()) {
    ExpectedAnswerKind.MULTIPLE_CHOICE -> "multiple_choice"
    ExpectedAnswerKind.INTEGER -> "integer"
    ExpectedAnswerKind.FREE_TEXT -> "free_text"
}
