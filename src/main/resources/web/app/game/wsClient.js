import { wsUrl } from "../core/net.js";
import { ActionType } from "./domain/gameState.js";

let activeWs = null;

export function connectWs({ dispatch, payload }) {
    closeWs(dispatch);

    const ws = new WebSocket(wsUrl());
    activeWs = ws;

    ws.addEventListener("open", () => {
        if (activeWs !== ws) return;
        dispatch({ type: ActionType.WS_OPENED });
        const joinFrame = {
            type: "join_session",
            sessionId: payload.sessionId,
            opponentSpecId: payload.opponentSpecId,
            nickname: payload.nickname,
            locale: payload.locale || navigator.language || "en",
        };
        ws.send(JSON.stringify(joinFrame));
    });

    ws.addEventListener("close", () => {
        if (activeWs !== ws) return;
        activeWs = null;
        dispatch({ type: ActionType.WS_CLOSED, payload: { toastOnClose: payload.toastOnClose } });
    });

    ws.addEventListener("error", () => {
        if (activeWs !== ws) return;
        activeWs = null;
        dispatch({ type: ActionType.WS_CLOSED, payload: { toastOnClose: payload.toastOnClose } });
    });

    ws.addEventListener("message", (event) => {
        if (activeWs !== ws) return;
        let msg;
        try {
            msg = JSON.parse(event.data);
        } catch {
            dispatch({
                type: ActionType.SERVER_EVENT_RECEIVED,
                payload: { type: "session_error", errorCode: "invalid_json", message: "invalid JSON from server" },
            });
            return;
        }
        dispatch({ type: ActionType.SERVER_EVENT_RECEIVED, payload: msg });
    });
}

export function sendWs(frame) {
    if (!activeWs || activeWs.readyState !== WebSocket.OPEN) {
        return false;
    }
    activeWs.send(JSON.stringify(frame));
    return true;
}

export function closeWs(dispatch) {
    if (!activeWs) return;
    try {
        activeWs.close();
    } catch {
        // ignore
    } finally {
        if (dispatch) {
            dispatch({ type: ActionType.WS_CLOSED, payload: { toastOnClose: false } });
        }
        activeWs = null;
    }
}
