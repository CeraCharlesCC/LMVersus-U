package io.github.ceracharlescc.lmversusu.internal.domain.vo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed class Answer {

    @Serializable
    @SerialName("multiple_choice")
    data class MultipleChoice(val choiceIndex: Int) : Answer()

    @Serializable
    @SerialName("integer")
    data class Integer(val value: Int) : Answer()

    @Serializable
    @SerialName("free_text")
    data class FreeText(val text: String) : Answer()
}