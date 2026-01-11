import { t } from "../core/i18n.js";
import { readErrorBody } from "../core/net.js";
import { STORAGE_KEY_NICKNAME } from "../core/state.js";
import { newCommandId } from "../core/utils.js";
import { toast } from "../ui/toast.js";
import { cancelVsTransition, playVsTransition } from "../ui/vsTransition.js";
import { ActionType, EffectType, GamePhase } from "./domain/gameState.js";
import { closeWs, connectWs, sendWs } from "./wsClient.js";

let tickHandle = null;

function handleToast(payload) {
    const key = payload?.key;
    const titleError = t("toastError");
    const titleSession = t("toastSession");

    if (key === "nickname_required") {
        toast(titleError, `${t("nickname")} is required`, "error");
        return;
    }
    if (key === "nickname_too_long") {
        toast(titleError, t("nicknameTooLong", { n: payload?.maxLen || 0 }), "error");
        return;
    }
    if (key === "nickname_invalid_chars") {
        toast(titleError, t("nicknameInvalidChars"), "error");
        return;
    }
    if (key === "opponent_required") {
        toast(titleError, `${t("opponent")} is required`, "error");
        return;
    }
    if (key === "start_round_no_session") {
        toast(titleError, "no sessionId", "error");
        return;
    }
    if (key === "start_round_no_ws") {
        toast(titleError, "websocket not connected", "error");
        return;
    }
    if (key === "submit_not_ready") {
        toast(titleError, "round not ready", "error");
        return;
    }
    if (key === "answer_choose_option") {
        toast(titleError, "choose one option", "error");
        return;
    }
    if (key === "answer_invalid_int") {
        toast(titleError, "enter an integer", "error");
        return;
    }
    if (key === "answer_empty_text") {
        toast(titleError, "enter text", "error");
        return;
    }
    if (key === "give_up_failed") {
        toast(titleError, payload?.message || t("giveUpFailed"), "error");
        return;
    }
    if (key === "session_joined") {
        toast(titleSession, t("toastNetOk"));
        return;
    }
    if (key === "ws_closed") {
        toast(titleSession, t("toastNetBad"));
        return;
    }
    if (key === "session_terminated") {
        const reason = String(payload?.reason || "");
        const line =
            reason === "timeout"
                ? t("sessionEndIdle")
                : reason === "max_lifespan"
                    ? t("sessionEndMax")
                    : reason === "completed"
                        ? t("sessionEndCompleted")
                        : t("sessionEndGeneric");
        toast(titleSession, line);
        return;
    }
    if (key === "session_error") {
        const code = String(payload?.code || "");
        if (code === "rate_limited" || code === "session_limit_exceeded") {
            toast(t("rateLimitedTitle"), payload?.message || t("rateLimitedMsgNoTime"), "warn", 5200);
            return;
        }
        toast(titleError, `${code}: ${payload?.message || ""}`, "error");
        return;
    }
    toast(titleError, payload?.message || "error", "error");
}

export function runEffects(effects, dispatch, getState) {
    for (const effect of effects) {
        if (!effect) continue;
        switch (effect.type) {
            case EffectType.WS_CONNECT:
                connectWs({ dispatch, payload: effect.payload });
                break;
            case EffectType.WS_SEND: {
                const ok = sendWs(effect.payload);
                if (!ok) {
                    handleToast({ key: "start_round_no_ws" });
                }
                break;
            }
            case EffectType.WS_CLOSE:
                closeWs(dispatch);
                break;
            case EffectType.TOAST:
                handleToast(effect.payload);
                break;
            case EffectType.SET_HASH:
                if (effect.payload?.sessionId) {
                    location.hash = `session=${encodeURIComponent(effect.payload.sessionId)}`;
                } else {
                    location.hash = "";
                }
                break;
            case EffectType.SAVE_NICKNAME:
                if (effect.payload?.nickname) {
                    localStorage.setItem(STORAGE_KEY_NICKNAME, effect.payload.nickname);
                }
                break;
            case EffectType.PLAY_VS_TRANSITION: {
                const humanName = effect.payload?.humanName || "You";
                const llmName = effect.payload?.llmName || "LLM";
                const promise = playVsTransition({
                    humanName,
                    llmName,
                    holdMs: 3000,
                    onSwitch: () => dispatch({ type: ActionType.VS_TRANSITION_SWITCHED }),
                });
                Promise.resolve(promise)
                    .then(() => {
                        const state = getState();
                        const shouldAutoStart =
                            state.ui.autoStartRound && state.phase === GamePhase.SESSION_READY;
                        dispatch({ type: ActionType.VS_TRANSITION_DONE });
                        if (shouldAutoStart) {
                            dispatch({
                                type: ActionType.INTENT_START_ROUND,
                                payload: { commandId: newCommandId() },
                            });
                        }
                    })
                    .catch(() => dispatch({ type: ActionType.VS_TRANSITION_DONE }));
                break;
            }
            case EffectType.CANCEL_VS_TRANSITION:
                cancelVsTransition();
                dispatch({ type: ActionType.VS_TRANSITION_DONE });
                break;
            case EffectType.START_TICKER:
                if (!tickHandle) {
                    tickHandle = setInterval(() => {
                        dispatch({ type: ActionType.TIMER_TICK, payload: { now: Date.now() } });
                    }, 100);
                }
                break;
            case EffectType.STOP_TICKER:
                if (tickHandle) {
                    clearInterval(tickHandle);
                    tickHandle = null;
                }
                break;
            case EffectType.TERMINATE_SESSION:
                fetch("/api/v1/player/active-session/terminate", {
                    method: "POST",
                    credentials: "include",
                })
                    .then(async (res) => {
                        if (res.ok || res.status === 204) {
                            dispatch({ type: ActionType.GIVE_UP_SUCCEEDED });
                            return;
                        }
                        const errBody = await readErrorBody(res);
                        dispatch({
                            type: ActionType.GIVE_UP_FAILED,
                            payload: { message: errBody || t("giveUpFailed") },
                        });
                    })
                    .catch((error) => {
                        dispatch({
                            type: ActionType.GIVE_UP_FAILED,
                            payload: { message: error?.message || t("giveUpFailed") },
                        });
                    });
                break;
            default:
                break;
        }
    }
}
