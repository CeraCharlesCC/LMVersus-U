package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import kotlin.uuid.Uuid

internal sealed interface SessionCommand {
    data class JoinSession(
        val sessionId: Uuid,
        val playerId: Uuid,
        val nickname: String,
        val response: CompletableDeferred<SessionActor.JoinResponse>,
    ) : SessionCommand

    data class StartNextRound(
        val sessionId: Uuid,
        val playerId: Uuid,
        val commandId: Uuid,
    ) : SessionCommand

    data class SubmitAnswer(
        val sessionId: Uuid,
        val playerId: Uuid,
        val roundId: Uuid,
        val nonceToken: String,
        val answer: Answer,
        val clientSentAt: Instant?,
        val commandId: Uuid,
    ) : SessionCommand

    data class StartLlmForRound(
        val roundId: Uuid,
    ) : SessionCommand

    data class LlmStreamReady(
        val roundId: Uuid,
        val getWithheldReasoning: (suspend () -> String)?,
    ) : SessionCommand

    data class LlmReasoningDeltaReceived(
        val roundId: Uuid,
        val deltaText: String,
    ) : SessionCommand

    data class LlmReasoningTruncatedReceived(
        val roundId: Uuid,
        val droppedChars: Int,
    ) : SessionCommand

    data class LlmReasoningEndedReceived(
        val roundId: Uuid,
    ) : SessionCommand

    data class LlmFinalAnswerReceived(
        val roundId: Uuid,
        val answer: LlmAnswer,
    ) : SessionCommand

    data class LlmStreamErrored(
        val roundId: Uuid,
        val message: String,
        val cause: Throwable? = null,
    ) : SessionCommand

    data class Timeout(val reason: String = "timeout") : SessionCommand

    data class RoundDeadlineReached(
        val roundId: Uuid,
    ) : SessionCommand
}
