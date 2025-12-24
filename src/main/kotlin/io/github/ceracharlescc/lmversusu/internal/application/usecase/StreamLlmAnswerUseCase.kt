package io.github.ceracharlescc.lmversusu.internal.application.usecase

import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import io.github.ceracharlescc.lmversusu.internal.domain.port.LlmOpponent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmRoundContext
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmStreamEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid


@Singleton
internal class StreamLlmAnswerUseCase @Inject constructor(
    private val llmOpponent: LlmOpponent,
    private val gameEventBus: GameEventBus
) {
    suspend fun execute(sessionId: Uuid, roundId: Uuid, context: LlmRoundContext) {
        llmOpponent.respond(context) { event ->
            when (event) {
                is LlmStreamEvent.ReasoningDelta -> {
                    gameEventBus.publish(
                        GameEvent.LlmReasoningDelta(
                            sessionId = sessionId,
                            roundId = roundId,
                            deltaText = event.deltaText,
                            emittedTokenCount = event.emittedTokenCount,
                            totalTokenCount = event.totalTokenCount
                        )
                    )
                }

                is LlmStreamEvent.FinalAnswer -> {
                    gameEventBus.publish(
                        GameEvent.LlmFinalAnswer(
                            sessionId = sessionId,
                            roundId = roundId,
                            answer = stringify(event.answer),
                        )
                    )
                }
            }
        }
    }

    private fun stringify(answer: Answer): String {
        return when (answer) {
            is Answer.MultipleChoice -> answer.choiceIndex.toString()
            is Answer.Integer -> answer.value.toString()
            is Answer.FreeText -> answer.text
        }
    }
}