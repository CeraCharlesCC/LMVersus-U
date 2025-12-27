package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.application.port.AnswerVerifier
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.LlmPlayerGateway
import io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase
import io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.application.service.LlmStreamOrchestrator
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class SessionManager @Inject constructor(
    private val opponentSpecRepository: OpponentSpecRepository,
    private val gameEventBus: GameEventBus,
    private val startRoundUseCase: StartRoundUseCase,
    private val submitAnswerUseCase: SubmitAnswerUseCase,
    private val answerVerifier: AnswerVerifier,
    private val llmPlayerGateway: LlmPlayerGateway,
    private val llmStreamOrchestrator: LlmStreamOrchestrator,
    private val resultsRepository: ResultsRepository,
    private val clock: Clock,
) {
    sealed interface JoinResult {
        data class Success(
            val sessionId: Uuid,
            val playerId: Uuid,
            val opponentSpecId: String,
            val nickname: String,
        ) : JoinResult

        data class Failure(
            val sessionId: Uuid? = null,
            val errorCode: String,
            val message: String,
        ) : JoinResult
    }

    sealed interface CommandResult {
        data class Success(val sessionId: Uuid) : CommandResult
        data class Failure(
            val sessionId: Uuid? = null,
            val errorCode: String,
            val message: String,
        ) : CommandResult
    }

    private val actors = ConcurrentHashMap<Uuid, SessionActor>()

    suspend fun joinSession(
        sessionId: Uuid,
        playerId: Uuid,
        nickname: String,
        opponentSpecId: String,
    ): JoinResult {
        val opponentSpec = opponentSpecRepository.findById(opponentSpecId)
            ?: return JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "opponent_not_found",
                message = "opponent spec not found",
            )

        val actor = actors.computeIfAbsent(sessionId) {
            SessionActor(
                sessionId = sessionId,
                opponentSpec = opponentSpec,
                gameEventBus = gameEventBus,
                startRoundUseCase = startRoundUseCase,
                submitAnswerUseCase = submitAnswerUseCase,
                answerVerifier = answerVerifier,
                llmPlayerGateway = llmPlayerGateway,
                llmStreamOrchestrator = llmStreamOrchestrator,
                resultsRepository = resultsRepository,
                clock = clock,
            )
        }

        if (actor.opponentSpecId != opponentSpecId) {
            return JoinResult.Failure(
                sessionId = sessionId,
                errorCode = "opponent_mismatch",
                message = "session already uses a different opponent",
            )
        }

        actor.submit(
            SessionCommand.JoinSession(
                sessionId = sessionId,
                playerId = playerId,
                nickname = nickname,
            )
        )

        return JoinResult.Success(
            sessionId = sessionId,
            playerId = playerId,
            opponentSpecId = opponentSpecId,
            nickname = nickname,
        )
    }

    suspend fun startNextRound(sessionId: Uuid, playerId: Uuid): CommandResult {
        val actor = actors[sessionId]
            ?: return CommandResult.Failure(
                sessionId = sessionId,
                errorCode = "session_not_found",
                message = "session not found",
            )

        actor.submit(SessionCommand.StartNextRound(sessionId = sessionId, playerId = playerId))
        return CommandResult.Success(sessionId)
    }

    suspend fun submitAnswer(
        sessionId: Uuid,
        playerId: Uuid,
        roundId: Uuid,
        nonceToken: String,
        answer: io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer,
        clientSentAt: Instant?,
    ): CommandResult {
        val actor = actors[sessionId]
            ?: return CommandResult.Failure(
                sessionId = sessionId,
                errorCode = "session_not_found",
                message = "session not found",
            )

        actor.submit(
            SessionCommand.SubmitAnswer(
                sessionId = sessionId,
                playerId = playerId,
                roundId = roundId,
                nonceToken = nonceToken,
                answer = answer,
                clientSentAt = clientSentAt,
            )
        )
        return CommandResult.Success(sessionId)
    }
}
