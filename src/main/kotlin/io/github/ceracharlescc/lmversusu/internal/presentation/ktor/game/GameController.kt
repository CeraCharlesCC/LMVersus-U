package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameEvent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.ClientIdentity
import io.github.ceracharlescc.lmversusu.internal.infrastructure.game.SessionManager
import io.github.ceracharlescc.lmversusu.internal.utils.NicknameValidator
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)

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
            val roundSnapshot: GameEvent.RoundStarted? = null,
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
        playerId: String,
        clientIpAddress: String,
        opponentSpecId: String,
        nickname: String,
    ): JoinResult {
        if (opponentSpecId.isBlank()) {
            return JoinResult.Failure(errorCode = "invalid_opponent", message = "opponentSpecId is required")
        }

        val trimmedNickname = nickname.trim()
        when (val validationResult = NicknameValidator.validate(trimmedNickname)) {
            is NicknameValidator.ValidationResult.Invalid -> {
                return JoinResult.Failure(
                    errorCode = validationResult.errorCode,
                    message = validationResult.message
                )
            }

            is NicknameValidator.ValidationResult.Valid -> {
                // Continue with join process
            }
        }

        val parsedSessionId = sessionId?.let { parseUuidOrNull(it) }
        if (sessionId != null && parsedSessionId == null) {
            return JoinResult.Failure(errorCode = "invalid_session", message = "sessionId is invalid")
        }

        val parsedPlayerId = parseUuidOrNull(playerId) ?: return JoinResult.Failure(
            errorCode = "invalid_player",
            message = "playerId is invalid"
        )

        val normalizedIpAddress = clientIpAddress.ifBlank { "unknown" }
        val clientIdentity = ClientIdentity(
            playerId = parsedPlayerId,
            ipAddress = normalizedIpAddress,
        )
        return when (
            val result = sessionManager.joinSession(
                parsedSessionId,
                clientIdentity,
                trimmedNickname,
                opponentSpecId
            )
        ) {
            is SessionManager.JoinResult.Success -> JoinResult.Success(
                sessionId = result.sessionId,
                playerId = result.playerId,
                opponentSpecId = result.opponentSpecId,
                nickname = result.nickname,
                roundSnapshot = result.roundSnapshot,
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
        commandId: String?,
    ): CommandResult {
        val parsedSessionId = parseUuidOrNull(sessionId)
        val parsedPlayerId = parseUuidOrNull(playerId)
        if (parsedSessionId == null || parsedPlayerId == null) {
            return CommandResult.Failure(errorCode = "invalid_request", message = "sessionId or playerId is invalid")
        }

        val parsedCommandId = if (commandId.isNullOrBlank()) {
            Uuid.random()
        } else {
            parseUuidOrNull(commandId) ?: return CommandResult.Failure(
                sessionId = parsedSessionId,
                errorCode = "invalid_command_id",
                message = "commandId is invalid",
            )
        }

        return when (val result = sessionManager.startNextRound(parsedSessionId, parsedPlayerId, parsedCommandId)) {
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
        commandId: String?,
        nonceToken: String,
        clientSentAtEpochMs: Long?,
        answer: io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer,
    ): CommandResult {
        val parsedSessionId = parseUuidOrNull(sessionId)
        val parsedPlayerId = parseUuidOrNull(playerId)
        val parsedRoundId = parseUuidOrNull(roundId)
        if (parsedSessionId == null || parsedPlayerId == null || parsedRoundId == null) {
            return CommandResult.Failure(
                errorCode = "invalid_request",
                message = "sessionId, playerId, or roundId is invalid"
            )
        }
        if (nonceToken.isBlank()) {
            return CommandResult.Failure(
                sessionId = parsedSessionId,
                errorCode = "invalid_nonce",
                message = "nonceToken is required",
            )
        }

        val clientSentAt = clientSentAtEpochMs?.let { Instant.ofEpochMilli(it) }
        val parsedCommandId = if (commandId.isNullOrBlank()) {
            Uuid.random()
        } else {
            parseUuidOrNull(commandId) ?: return CommandResult.Failure(
                sessionId = parsedSessionId,
                errorCode = "invalid_command_id",
                message = "commandId is invalid",
            )
        }

        return when (
            val result = sessionManager.submitAnswer(
                sessionId = parsedSessionId,
                playerId = parsedPlayerId,
                roundId = parsedRoundId,
                commandId = parsedCommandId,
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

    suspend fun touchSession(sessionId: String): Boolean {
        val parsedSessionId = parseUuidOrNull(sessionId) ?: return false
        return sessionManager.touchSession(parsedSessionId) is SessionManager.TouchResult.Success
    }

    private fun parseUuidOrNull(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
}
