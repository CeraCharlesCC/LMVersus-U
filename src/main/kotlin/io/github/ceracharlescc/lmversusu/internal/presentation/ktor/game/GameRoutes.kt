package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game

import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventListener
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.GameEventFrameMapper
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsClientFrame
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsGameFrame
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsJoinSession
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsSessionError
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsSessionJoined
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsStartRoundRequest
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.WsSubmitAnswer
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

internal fun Route.gameWebSocket(
    gameController: GameController,
    gameEventBus: GameEventBus,
) {
    route("/ws") {
        webSocket("/game") {
            val json = Json { ignoreUnknownKeys = true }
            var subscribedSessionId: Uuid? = null

            val listener = GameEventListener { event ->
                val frame = GameEventFrameMapper.toFrame(event) ?: return@GameEventListener
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
                            val result = gameController.joinSession(
                                sessionId = clientFrame.sessionId,
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
                            val result = gameController.startNextRound(
                                sessionId = clientFrame.sessionId,
                                playerId = clientFrame.playerId,
                            )
                            if (result is GameController.CommandResult.Failure) {
                                sendError(result.sessionId, result.errorCode, result.message)
                            } else if (result is GameController.CommandResult.Success) {
                                subscribeTo(result.sessionId)
                            }
                        }

                        is WsSubmitAnswer -> {
                            val result = gameController.submitAnswer(
                                sessionId = clientFrame.sessionId,
                                playerId = clientFrame.playerId,
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

                        else -> Unit
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
) {
    gameWebSocket(gameController, gameEventBus)
}

private suspend fun io.ktor.websocket.WebSocketSession.sendFrame(
    json: Json,
    frame: WsGameFrame,
) {
    val payload = json.encodeToString(WsGameFrame.serializer(), frame)
    outgoing.send(Frame.Text(payload))
}
