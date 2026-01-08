import {LANG, t} from "../core/i18n.js";
import {state} from "../core/state.js";
import {toast} from "../ui/toast.js";
import {openWsAndJoin} from "../game/ws.js";

export async function ensurePlayerSession() {
    const res = await fetch("/api/v1/player/session", {credentials: "include"});
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    const data = await res.json();
    state.playerId = data.playerId;
    state.issuedAt = data.issuedAtEpochMs;
}

/** ---- Session Recovery (F5) ---- */
export async function tryRecoverActiveSession() {
    try {
        const res = await fetch("/api/v1/player/active-session", {credentials: "include"});
        if (res.status === 204) {
            // No active session, stay on lobby
            return false;
        }
        if (!res.ok) {
            // error - just ignore and stay on lobby
            return false;
        }
        const data = await res.json();
        if (!data.activeSessionId || !data.opponentSpecId) {
            return false;
        }

        // Try to recover display name from opponent specs
        const models = [...(state.models.LIGHTWEIGHT || []), ...(state.models.PREMIUM || [])];
        const matchingModel = models.find((m) => m.id === data.opponentSpecId);
        const displayName = matchingModel?.metadata.displayName || data.opponentSpecId

        // We have an active session, attempt to rejoin via WebSocket
        toast(t("toastSession"), t("recovering"));

        state.sessionId = data.activeSessionId;
        state.opponentSpecId = data.opponentSpecId;
        state.opponentDisplayName = displayName;
        state.opponentQuestionSetDisplayName = matchingModel?.metadata?.questionSetDisplayName || null;
        state.opponentQuestionSetDescription = matchingModel?.metadata?.questionSetDescription || null;
        state.opponentQuestionSetDescriptionI18nKey = matchingModel?.metadata?.questionSetDescriptionI18nKey || null;
        state.opponentDifficulty = matchingModel?.metadata?.difficulty || null;
        // Nickname is not returned by this endpoint, so we use a placeholder
        state.nickname = state.nickname || t("yourId");

        openWsAndJoin({
            sessionId: data.activeSessionId,
            opponentSpecId: data.opponentSpecId,
            nickname: state.nickname,
            locale: navigator.language || (LANG === "ja" ? "ja-JP" : "en"),
        });

        return true;
    } catch (e) {
        // Network error - ignore and stay on lobby
        console.warn("Session recovery failed:", e);
        return false;
    }
}
