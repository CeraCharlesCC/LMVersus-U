import {wsUrl} from "../core/net.js";
import {state} from "../core/state.js";
import {setNet} from "../ui/netIndicator.js";
import {toast} from "../ui/toast.js";
import {t} from "../core/i18n.js";
import {handleServerEvent} from "./serverEvents.js";

export function closeWs() {
    const ws = state.ws;
    state.ws = null;
    state.wsOpen = false;
    setNet(false);
    try {
        ws?.close();
    } catch {
        // ignore
    }
}

export function openWsAndJoin({
                                  sessionId = null,
                                  opponentSpecId,
                                  nickname,
                                  locale,
                                  toastOnOpen = true,
                                  toastOnClose = true,
                              } = {}) {
    closeWs();

    const ws = new WebSocket(wsUrl());
    state.ws = ws;

    ws.addEventListener("open", () => {
        if (state.ws !== ws) return;
        state.wsOpen = true;
        setNet(true);
        if (toastOnOpen) {
            toast(t("toastSession"), t("toastNetOk"));
        }

        const joinFrame = {
            type: "join_session",
            sessionId,
            opponentSpecId,
            nickname,
            locale: locale || navigator.language || "en",
        };
        ws.send(JSON.stringify(joinFrame));
    });

    ws.addEventListener("close", () => {
        if (state.ws !== ws) return;
        state.wsOpen = false;
        setNet(false);
        if (toastOnClose) {
            toast(t("toastSession"), t("toastNetBad"));
        }
    });

    ws.addEventListener("error", () => {
        if (state.ws !== ws) return;
        state.wsOpen = false;
        setNet(false);
    });

    ws.addEventListener("message", (ev) => {
        if (state.ws !== ws) return;
        let msg;
        try {
            msg = JSON.parse(ev.data);
        } catch {
            toast(t("toastError"), "invalid JSON from server");
            return;
        }
        handleServerEvent(msg, {closeWs});
    });
}

export function wsSend(frame) {
    if (!state.wsOpen || !state.ws) {
        toast(t("toastError"), "websocket not connected");
        return;
    }
    state.ws.send(JSON.stringify(frame));
}
