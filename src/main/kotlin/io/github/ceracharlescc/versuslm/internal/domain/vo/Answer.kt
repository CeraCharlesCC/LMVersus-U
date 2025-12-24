package io.github.ceracharlescc.versuslm.internal.domain.vo

internal sealed class Answer {
    data class MultipleChoice(val choiceIndex: Int) : Answer()

    data class Integer(val value: Int) : Answer()

    data class FreeText(val text: String) : Answer()
}
