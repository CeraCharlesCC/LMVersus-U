package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game

import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventBus
import io.github.ceracharlescc.lmversusu.internal.application.port.GameEventListener
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ServiceSession
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.game.ws.*
import io.github.ceracharlescc.lmversusu.internal.utils.ConnectionRateLimiter
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val HEARTBEAT_INTERVAL_MS = 30_000L

@OptIn(ExperimentalUuidApi::class)
internal fun Route.gameWebSocket(
    gameController: GameController,
    gameEventBus: GameEventBus,
    frameMapper: GameEventFrameMapper,
    sessionLimitConfig: AppConfig.SessionLimitConfig,
    serverConfig: AppConfig.ServerConfig
) {
    route("/ws") {
        webSocket("/game") {
            val originHeader = call.request.headers[HttpHeaders.Origin]

            if (!isAllowedWsOrigin(originHeader, serverConfig.corsAllowedHosts)) {
                close(
                    CloseReason(
                        CloseReason.Codes.VIOLATED_POLICY,
                        "origin_not_allowed"
                    )
                )
                return@webSocket
            }

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
            val clientIpAddress = call.request.origin.remoteAddress

            val json = Json { ignoreUnknownKeys = true }
            var subscribedSessionId: Uuid? = null
            var clientLocale: String? = null  // Connection-scoped locale for i18n
            var heartbeatJob: Job? = null
            val messageRateLimiter = ConnectionRateLimiter(
                windowMillis = sessionLimitConfig.websocketMessageWindowMillis,
                maxMessages = sessionLimitConfig.websocketMessageMaxMessages,
            )

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

            suspend fun subscribeTo(sessionId: Uuid, playerId: Uuid) {
                if (subscribedSessionId == sessionId) return
                subscribedSessionId?.let {
                    gameEventBus.unsubscribe(it, listener)
                    stopHeartbeat()
                }

                val authorized = gameEventBus.subscribe(sessionId, playerId, listener)
                if (!authorized) {
                    sendError(
                        sessionId,
                        "unauthorized",
                        "You are not authorized to subscribe to this session"
                    )

                    return
                }

                subscribedSessionId = sessionId
                startHeartbeat(sessionId.toString())
            }

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
                    if (!messageRateLimiter.tryConsume()) {
                        sendError(null, "rate_limited", "too many websocket messages")
                        close()
                        break
                    }
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
                                clientIpAddress = clientIpAddress,
                                opponentSpecId = clientFrame.opponentSpecId,
                                nickname = clientFrame.nickname,
                            )
                            when (result) {
                                is GameController.JoinResult.Success -> {
                                    subscribeTo(result.sessionId, result.playerId)
                                    sendFrame(
                                        json,
                                        WsSessionJoined(
                                            sessionId = result.sessionId.toString(),
                                            playerId = result.playerId.toString(),
                                            nickname = result.nickname,
                                            opponentSpecId = result.opponentSpecId,
                                        )
                                    )
                                    // Send round snapshot if rejoining mid-round
                                    result.roundSnapshot?.let { snapshot ->
                                        val snapshotFrame = frameMapper.toFrame(snapshot, clientLocale)
                                        if (snapshotFrame != null) {
                                            sendFrame(json, snapshotFrame)
                                        }
                                    }
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
                                commandId = clientFrame.commandId,
                            )
                            if (result is GameController.CommandResult.Failure) {
                                sendError(result.sessionId, result.errorCode, result.message)
                            } else if (result is GameController.CommandResult.Success) {
                                try {
                                    subscribeTo(result.sessionId, Uuid.parse(cookiePlayerId))
                                } catch (exception: IllegalArgumentException) {
                                    sendError(null, "invalid_player", "PlayerId is invalid")
                                }
                            }
                        }

                        is WsSubmitAnswer -> {
                            if (!validatePlayerId(clientFrame.playerId)) continue

                            val result = gameController.submitAnswer(
                                sessionId = clientFrame.sessionId,
                                playerId = cookiePlayerId, // Use cookie playerId
                                roundId = clientFrame.roundId,
                                commandId = clientFrame.commandId,
                                nonceToken = clientFrame.nonceToken,
                                clientSentAtEpochMs = clientFrame.clientSentAtEpochMs,
                                answer = clientFrame.answer,
                            )
                            if (result is GameController.CommandResult.Failure) {
                                sendError(result.sessionId, result.errorCode, result.message)
                            } else if (result is GameController.CommandResult.Success) {
                                try {
                                    subscribeTo(result.sessionId, Uuid.parse(cookiePlayerId))
                                } catch (exception: IllegalArgumentException) {
                                    sendError(null, "invalid_player", "PlayerId is invalid")
                                }
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
    sessionLimitConfig: AppConfig.SessionLimitConfig,
    serverConfig: AppConfig.ServerConfig
) {
    gameWebSocket(
        gameController,
        gameEventBus,
        frameMapper,
        sessionLimitConfig,
        serverConfig
    )
}

private suspend fun io.ktor.websocket.WebSocketSession.sendFrame(
    json: Json,
    frame: WsGameFrame,
) {
    val payload = json.encodeToString(WsGameFrame.serializer(), frame)
    outgoing.send(Frame.Text(payload))
}


private fun isAllowedWsOrigin(originHeader: String?, allowedHosts: List<String>): Boolean {
    if (originHeader.isNullOrBlank()) return false

    val uri = runCatching { URI(originHeader) }.getOrNull() ?: return false
    val originHost = (uri.host ?: return false).lowercase()

    val originPort = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        uri.scheme.equals("wss", ignoreCase = true) -> 443
        uri.scheme.equals("ws", ignoreCase = true) -> 80
        else -> -1
    }

    // Match rules:
    // - If an allow entry includes ":port", require host+port match.
    // - If an allow entry is just "host", match host regardless of port.
    for (raw in allowedHosts) {
        val entry = raw.trim().lowercase()
        if (entry.isEmpty()) continue

        val parts = entry.split(":", limit = 2)
        val allowedHost = parts[0]
        val allowedPort = parts.getOrNull(1)?.toIntOrNull()

        if (allowedPort != null) {
            if (originHost == allowedHost && originPort == allowedPort) return true
        } else {
            if (originHost == allowedHost) return true
        }
    }

    return false
}
