package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game

import io.github.ceracharlescc.lmversusu.internal.infrastructure.game.SessionManager
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class GameController @Inject constructor(
    private val sessionManager: SessionManager,
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

    suspend fun joinSession(
        sessionId: String?,
        opponentSpecId: String,
        nickname: String,
    ): JoinResult {
        if (opponentSpecId.isBlank()) {
            return JoinResult.Failure(errorCode = "invalid_opponent", message = "opponentSpecId is required")
        }
        if (nickname.isBlank()) {
            return JoinResult.Failure(errorCode = "invalid_nickname", message = "nickname is required")
        }

        val parsedSessionId = sessionId?.let { parseUuidOrNull(it) }
        if (sessionId != null && parsedSessionId == null) {
            return JoinResult.Failure(errorCode = "invalid_session", message = "sessionId is invalid")
        }

        val resolvedSessionId = parsedSessionId ?: Uuid.random()
        val playerId = Uuid.random()

        return when (val result = sessionManager.joinSession(resolvedSessionId, playerId, nickname, opponentSpecId)) {
            is SessionManager.JoinResult.Success -> JoinResult.Success(
                sessionId = result.sessionId,
                playerId = result.playerId,
                opponentSpecId = result.opponentSpecId,
                nickname = result.nickname,
            )

            is SessionManager.JoinResult.Failure -> JoinResult.Failure(
                sessionId = result.sessionId,
                errorCode = result.errorCode,
                message = result.message,
            )
        }
    }

    suspend fun startNextRound(
        sessionId: String,
        playerId: String,
    ): CommandResult {
        val parsedSessionId = parseUuidOrNull(sessionId)
        val parsedPlayerId = parseUuidOrNull(playerId)
        if (parsedSessionId == null || parsedPlayerId == null) {
            return CommandResult.Failure(errorCode = "invalid_request", message = "sessionId or playerId is invalid")
        }

        return when (val result = sessionManager.startNextRound(parsedSessionId, parsedPlayerId)) {
            is SessionManager.CommandResult.Success -> CommandResult.Success(result.sessionId)
            is SessionManager.CommandResult.Failure -> CommandResult.Failure(
                sessionId = result.sessionId,
                errorCode = result.errorCode,
                message = result.message,
            )
        }
    }

    suspend fun submitAnswer(
        sessionId: String,
        playerId: String,
        roundId: String,
        nonceToken: String,
        clientSentAtEpochMs: Long?,
        answer: io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer,
    ): CommandResult {
        val parsedSessionId = parseUuidOrNull(sessionId)
        val parsedPlayerId = parseUuidOrNull(playerId)
        val parsedRoundId = parseUuidOrNull(roundId)
        if (parsedSessionId == null || parsedPlayerId == null || parsedRoundId == null) {
            return CommandResult.Failure(errorCode = "invalid_request", message = "sessionId, playerId, or roundId is invalid")
        }
        if (nonceToken.isBlank()) {
            return CommandResult.Failure(
                sessionId = parsedSessionId,
                errorCode = "invalid_nonce",
                message = "nonceToken is required",
            )
        }

        val clientSentAt = clientSentAtEpochMs?.let { Instant.ofEpochMilli(it) }

        return when (
            val result = sessionManager.submitAnswer(
                sessionId = parsedSessionId,
                playerId = parsedPlayerId,
                roundId = parsedRoundId,
                nonceToken = nonceToken,
                answer = answer,
                clientSentAt = clientSentAt,
            )
        ) {
            is SessionManager.CommandResult.Success -> CommandResult.Success(result.sessionId)
            is SessionManager.CommandResult.Failure -> CommandResult.Failure(
                sessionId = result.sessionId,
                errorCode = result.errorCode,
                message = result.message,
            )
        }
    }

    private fun parseUuidOrNull(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
}
