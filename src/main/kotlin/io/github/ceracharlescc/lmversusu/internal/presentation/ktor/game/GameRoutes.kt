package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game

import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventListener
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ServiceSession
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val HEARTBEAT_INTERVAL_MS = 30_000L

@OptIn(ExperimentalUuidApi::class)
internal fun Route.gameWebSocket(
    gameController: GameController,
    gameEventBus: GameEventBus,
    frameMapper: GameEventFrameMapper,
) {
    route("/ws") {
        webSocket("/game") {
            val session = call.sessions.get<ServiceSession>()
            if (session == null) {
                val json = Json { ignoreUnknownKeys = true }
                sendFrame(
                    json,
                    WsSessionError(
                        sessionId = null,
                        errorCode = "auth_required",
                        message = "No session found. Please call GET /api/v1/player/session first.",
                    )
                )
                close()
                return@webSocket
            }

            val cookiePlayerId = session.playerId

            val json = Json { ignoreUnknownKeys = true }
            var subscribedSessionId: Uuid? = null
            var clientLocale: String? = null  // Connection-scoped locale for i18n
            var heartbeatJob: Job? = null

            val listener = GameEventListener { event ->
                val frame = frameMapper.toFrame(event, clientLocale) ?: return@GameEventListener
                sendFrame(json, frame)
            }

            suspend fun startHeartbeat(sessionId: String) {
                heartbeatJob?.cancel()
                heartbeatJob = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        gameController.touchSession(sessionId)
                    }
                }
            }

            suspend fun stopHeartbeat() {
                heartbeatJob?.cancel()
                heartbeatJob = null
            }

            suspend fun subscribeTo(sessionId: Uuid) {
                if (subscribedSessionId == sessionId) return
                subscribedSessionId?.let {
                    gameEventBus.unsubscribe(it, listener)
                    stopHeartbeat()
                }
                gameEventBus.subscribe(sessionId, listener)
                subscribedSessionId = sessionId
                startHeartbeat(sessionId.toString())
            }

            suspend fun sendError(sessionId: Uuid?, errorCode: String, message: String) {
                sendFrame(
                    json,
                    WsSessionError(
                        sessionId = sessionId?.toString(),
                        errorCode = errorCode,
                        message = message,
                    )
                )
            }

            /**
             * Validates that the playerId from the client frame matches the authenticated session.
             * This check should happen before any UUID parsing to provide clear auth error messages.
             * @return true if valid, false if mismatch (error already sent)
             */
            suspend fun validatePlayerId(clientPlayerId: String): Boolean {
                if (clientPlayerId != cookiePlayerId) {
                    sendError(null, "auth_mismatch", "PlayerId in request does not match session")
                    return false
                }
                return true
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    val clientFrame = runCatching {
                        json.decodeFromString(WsClientFrame.serializer(), text)
                    }.getOrElse {
                        sendError(null, "invalid_frame", "Unable to parse client frame")
                        continue
                    }

                    when (clientFrame) {
                        is WsJoinSession -> {
                            clientLocale = clientFrame.locale  // Capture locale from join request
                            val result = gameController.joinSession(
                                sessionId = clientFrame.sessionId,
                                playerId = cookiePlayerId, // Use cookie playerId
                                opponentSpecId = clientFrame.opponentSpecId,
                                nickname = clientFrame.nickname,
                            )
                            when (result) {
                                is GameController.JoinResult.Success -> {
                                    subscribeTo(result.sessionId)
                                    sendFrame(
                                        json,
                                        WsSessionJoined(
                                            sessionId = result.sessionId.toString(),
                                            playerId = result.playerId.toString(),
                                            nickname = result.nickname,
                                            opponentSpecId = result.opponentSpecId,
                                        )
                                    )
                                }

                                is GameController.JoinResult.Failure -> {
                                    sendError(result.sessionId, result.errorCode, result.message)
                                }
                            }
                        }

                        is WsStartRoundRequest -> {
                            if (!validatePlayerId(clientFrame.playerId)) continue

                            val result = gameController.startNextRound(
                                sessionId = clientFrame.sessionId,
                                playerId = cookiePlayerId, // Use cookie playerId
                            )
                            if (result is GameController.CommandResult.Failure) {
                                sendError(result.sessionId, result.errorCode, result.message)
                            } else if (result is GameController.CommandResult.Success) {
                                subscribeTo(result.sessionId)
                            }
                        }

                        is WsSubmitAnswer -> {
                            if (!validatePlayerId(clientFrame.playerId)) continue

                            val result = gameController.submitAnswer(
                                sessionId = clientFrame.sessionId,
                                playerId = cookiePlayerId, // Use cookie playerId
                                roundId = clientFrame.roundId,
                                nonceToken = clientFrame.nonceToken,
                                clientSentAtEpochMs = clientFrame.clientSentAtEpochMs,
                                answer = clientFrame.answer,
                            )
                            if (result is GameController.CommandResult.Failure) {
                                sendError(result.sessionId, result.errorCode, result.message)
                            } else if (result is GameController.CommandResult.Success) {
                                subscribeTo(result.sessionId)
                            }
                        }

                        is WsPing -> Unit  // Ping frames are handled automatically
                    }
                }
            } finally {
                stopHeartbeat()
                subscribedSessionId?.let { gameEventBus.unsubscribe(it, listener) }
            }
        }
    }
}

internal fun Route.gameRoutes(
    gameController: GameController,
    gameEventBus: GameEventBus,
    frameMapper: GameEventFrameMapper,
) {
    gameWebSocket(gameController, gameEventBus, frameMapper)
}

private suspend fun io.ktor.websocket.WebSocketSession.sendFrame(
    json: Json,
    frame: WsGameFrame,
) {
    val payload = json.encodeToString(WsGameFrame.serializer(), frame)
    outgoing.send(Frame.Text(payload))
}
