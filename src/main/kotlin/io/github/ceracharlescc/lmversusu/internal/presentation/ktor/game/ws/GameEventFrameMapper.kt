@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws

import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionLocalizer
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GameEventFrameMapper @Inject constructor(
    private val questionLocalizer: QuestionLocalizer,
) {
    suspend fun toFrame(event: GameEvent, locale: String?): WsGameFrame? = when (event) {
        is GameEvent.PlayerJoined -> WsPlayerJoined(
            sessionId = event.sessionId.toString(),
            playerId = event.playerId.toString(),
            nickname = event.nickname,
        )

        is GameEvent.RoundStarted -> {
            val localized = if (locale != null) {
                questionLocalizer.localize(
                    locale = locale,
                    questionId = event.questionId,
                    canonicalPrompt = event.questionPrompt,
                    canonicalChoices = event.choices,
                )
            } else {
                null
            }

            WsRoundStarted(
                sessionId = event.sessionId.toString(),
                questionId = event.questionId.toString(),
                roundId = event.roundId.toString(),
                roundNumber = event.roundNumber,
                questionPrompt = localized?.prompt ?: event.questionPrompt,
                choices = localized?.choices ?: event.choices,
                releasedAtEpochMs = event.releasedAt.toEpochMilli(),
                handicapMs = event.handicapMs,
                deadlineAtEpochMs = event.deadlineAt.toEpochMilli(),
                nonceToken = event.nonceToken,
            )
        }

        is GameEvent.RoundResolved -> WsRoundResolved(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
            correctAnswer = event.correctAnswer,
            humanCorrect = event.humanCorrect,
            llmCorrect = event.llmCorrect,
            humanScore = event.humanScore,
            llmScore = event.llmScore,
            winner = event.winner,
            reason = event.reason.name,
        )

        is GameEvent.SessionError -> WsSessionError(
            sessionId = event.sessionId.toString(),
            errorCode = event.errorCode,
            message = event.message,
        )

        is GameEvent.LlmReasoningDelta -> WsLlmReasoningDelta(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
            deltaText = event.deltaText,
            seq = event.seq.value,
        )

        is GameEvent.LlmReasoningTruncated -> WsLlmReasoningTruncated(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
            droppedChars = event.droppedChars,
        )

        is GameEvent.LlmFinalAnswer -> WsLlmFinalAnswer(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
            finalAnswer = event.answer.finalAnswer,
            reasoningSummary = event.answer.reasoningSummary,
            confidenceScore = event.answer.confidenceScore,
        )

        is GameEvent.LlmStreamError -> WsLlmStreamError(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
            message = event.message,
        )

        is GameEvent.LlmAnswerLockIn -> WsLlmAnswerLockIn(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
        )

        is GameEvent.LlmReasoningEnded -> WsLlmReasoningEnded(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
        )

        is GameEvent.LlmReasoningReveal -> WsLlmReasoningReveal(
            sessionId = event.sessionId.toString(),
            roundId = event.roundId.toString(),
            fullReasoning = event.fullReasoning,
        )

        is GameEvent.SessionTerminated -> WsSessionTerminated(
            sessionId = event.sessionId.toString(),
            reason = event.reason,
        )

        is GameEvent.SessionCreated,
        is GameEvent.SubmissionReceived,
        is GameEvent.LlmThinking,
        is GameEvent.SessionCompleted -> null
    }
}
