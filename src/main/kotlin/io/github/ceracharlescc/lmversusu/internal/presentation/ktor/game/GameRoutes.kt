package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game

import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventListener
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ServiceSession
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.GameEventFrameMapper
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsClientFrame
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsGameFrame
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsJoinSession
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsPing
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsSessionError
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsSessionJoined
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsStartRoundRequest
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsSubmitAnswer
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

            val listener = GameEventListener { event ->
                val frame = runBlocking {
                    frameMapper.toFrame(event, clientLocale)
                } ?: return@GameEventListener
                sendFrame(json, frame)
            }

            suspend fun subscribeTo(sessionId: Uuid) {
                if (subscribedSessionId == sessionId) return
                subscribedSessionId?.let { gameEventBus.unsubscribe(it, listener) }
                gameEventBus.subscribe(sessionId, listener)
                subscribedSessionId = sessionId
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
                            if (clientFrame.playerId != cookiePlayerId) {
                                sendError(null, "auth_mismatch", "PlayerId in request does not match session")
                                continue
                            }

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
                            if (clientFrame.playerId != cookiePlayerId) {
                                sendError(null, "auth_mismatch", "PlayerId in request does not match session")
                                continue
                            }

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
