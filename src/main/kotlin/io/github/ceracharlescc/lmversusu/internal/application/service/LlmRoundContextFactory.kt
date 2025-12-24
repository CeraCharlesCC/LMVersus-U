package io.github.ceracharlescc.lmversusu.internal.application.service

import io.github.ceracharlescc.lmversusu.internal.domain.entity.Question
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmRoundContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LlmRoundContextFactory @Inject constructor() {

    fun create(question: Question, llmProfile: LlmProfile): LlmRoundContext {
        val llmPrompt = buildString {
            appendLine("You are playing a timed quiz game.")
            appendLine("Return ONLY the final answer in the requested format.")
            appendLine()

            appendLine("Question:")
            appendLine(question.prompt.trim())
            appendLine()

            question.choices?.let { choices ->
                appendLine("Choices (0-based index):")
                choices.forEachIndexed { index, choice ->
                    appendLine("$index) ${choice.trim()}")
                }
                appendLine()
                appendLine("Answer format: a single integer choiceIndex (0, 1, 2, ...).")
            } ?: run {
                appendLine("Answer format: respond with the best possible answer.")
            }
        }

        return LlmRoundContext(
            questionId = question.questionId,
            prompt = llmPrompt,
            choices = question.choices,
            llmProfile = llmProfile
        )
    }
}