import { LANG, t } from "../core/i18n.js";
import { toast } from "../ui/toast.js";

export async function ensurePlayerSession() {
    const res = await fetch("/api/v1/player/session", {credentials: "include"});
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
    return res.json();
}

/** ---- Session Recovery (F5) ---- */
export async function tryRecoverActiveSession(models, fallbackNickname) {
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
        const flat = [...(models.LIGHTWEIGHT || []), ...(models.PREMIUM || [])];
        const matchingModel = flat.find((m) => m.id === data.opponentSpecId);
        const displayName = matchingModel?.metadata.displayName || data.opponentSpecId;

        // We have an active session, attempt to rejoin via WebSocket
        toast(t("toastSession"), t("recovering"));
        return {
            activeSessionId: data.activeSessionId,
            opponentSpecId: data.opponentSpecId,
            opponentDisplayName: displayName,
            opponentQuestionSetDisplayName: matchingModel?.metadata?.questionSetDisplayName || null,
            opponentQuestionSetDescription: matchingModel?.metadata?.questionSetDescription || null,
            opponentQuestionSetDescriptionI18nKey: matchingModel?.metadata?.questionSetDescriptionI18nKey || null,
            opponentDifficulty: matchingModel?.metadata?.difficulty || null,
            nickname: fallbackNickname || t("yourId"),
            locale: navigator.language || (LANG === "ja" ? "ja-JP" : "en"),
        };
    } catch (e) {
        // Network error - ignore and stay on lobby
        console.warn("Session recovery failed:", e);
        return false;
    }
}
