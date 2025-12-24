package io.github.ceracharlescc.versuslm.internal.presentation.ktor.game

import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

internal fun Route.gameWebSocket() {
    route("/ws") {
        webSocket("/game") {
            // TODO: Integrate with game session management
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    outgoing.send(Frame.Text("YOU SAID: $text"))
                    if (text.equals("bye", ignoreCase = true)) {
                        outgoing.send(Frame.Text("FAREWELL!"))
                        close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                    }
                }
            }
        }
    }
}

internal fun Route.gameRoutes() {
    gameWebSocket()
}
