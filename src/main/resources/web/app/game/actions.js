import { $ } from "../core/dom.js";
import { LANG, t } from "../core/i18n.js";
import { state, MAX_NICKNAME_LEN, STORAGE_KEY_NICKNAME } from "../core/state.js";
import { toast } from "../ui/toast.js";
import { safeLsSet, newCommandId } from "../core/utils.js";
import { openWsAndJoin, wsSend, closeWs } from "./ws.js";
import { resetRoundUi, updateMatchupUi, setSubmitFrozen, enforceDeadline, applyFreeAnswerMode } from "./roundUi.js";
import { showLobby } from "./uiScreens.js";
import { readErrorBody } from "../core/net.js";

/** ---- Give Up (Terminate Active Session) ---- */
export async function giveUp() {
    if (!confirm(t("giveUpConfirm"))) {
        return;
    }

    try {
        const res = await fetch("/api/v1/player/active-session/terminate", {
            method: "POST",
            credentials: "include",
        });
        if (!res.ok && res.status !== 204) {
            const errBody = await readErrorBody(res);
            toast(t("toastError"), errBody || t("giveUpFailed"), "error");
            return;
        }

        // Close websocket and return to lobby
        closeWs();
        showLobby();
        resetRoundUi();
        state.sessionId = null;
    } catch (e) {
        toast(t("toastError"), t("giveUpFailed"), "error");
    }
}

export function startMatch(mode) {
    const nickname = (mode === "LIGHTWEIGHT" ? $("#nicknameLight").value : $("#nicknamePremium").value).trim();
    // Use the hidden input for value
    const valInput = mode === "LIGHTWEIGHT" ? $("#opponentLightVal") : $("#opponentPremiumVal");
    const opponentSpecId = valInput.value;

    if (!nickname) {
        toast(t("toastError"), `${t("nickname")} is required`, "error");
        return;
    }
    if (nickname.length > MAX_NICKNAME_LEN) {
        toast(t("toastError"), t("nicknameTooLong", { n: MAX_NICKNAME_LEN }), "error");
        return;
    }
    for (const ch of nickname) {
        if (/[\u0000-\u001F\u007F]/.test(ch)) {
            toast(t("toastError"), t("nicknameInvalidChars"), "error");
            return;
        }
    }
    if (!opponentSpecId) {
        toast(t("toastError"), `${t("opponent")} is required`, "error");
        return;
    }

    const models = state.models[mode] || [];
    const selectedModel = models.find((m) => m.id === opponentSpecId);
    const displayName = selectedModel?.metadata?.displayName || selectedModel?.id || "LLM";

    state.mode = mode;
    state.nickname = nickname;
    state.opponentSpecId = opponentSpecId;
    state.opponentDisplayName = displayName;
    state.opponentQuestionSetDisplayName = selectedModel?.metadata?.questionSetDisplayName || null;
    state.opponentDifficulty = selectedModel?.metadata?.difficulty || null;

    safeLsSet(STORAGE_KEY_NICKNAME, nickname);

    updateMatchupUi();

    openWsAndJoin({
        sessionId: null,
        opponentSpecId,
        nickname,
        locale: navigator.language || (LANG === "ja" ? "ja-JP" : "en"),
    });
}

export function startRound() {
    if (!state.sessionId) {
        toast(t("toastError"), "no sessionId");
        return;
    }
    wsSend({
        type: "start_round_request",
        sessionId: state.sessionId,
        playerId: state.playerId, // server validates with cookie identity
        commandId: newCommandId(),
    });
}

export function submitAnswer() {
    if (!state.roundId || !state.sessionId || !state.nonceToken) {
        toast(t("toastError"), "round not ready");
        return;
    }
    if (Date.now() > state.deadlineAt) {
        enforceDeadline();
        return;
    }
    if (state.submitted) return;

    let answer;
    if (Array.isArray(state.choices) && state.choices.length) {
        if (state.selectedChoiceIndex == null) {
            toast(t("toastError"), "choose one option");
            return;
        }
        answer = { type: "multiple_choice", choiceIndex: state.selectedChoiceIndex };
    } else {
        if (state.freeAnswerMode === "int") {
            const raw = $("#intValue").value.trim();
            if (!raw || !/^-?\d+$/.test(raw)) {
                toast(t("toastError"), "enter an integer");
                return;
            }
            answer = { type: "integer", value: parseInt(raw, 10) };
        } else {
            const text = $("#freeText").value.trim();
            if (!text) {
                toast(t("toastError"), "enter text");
                return;
            }
            answer = { type: "free_text", text };
        }
    }

    state.submitted = true;
    state.humanAnswer = answer;
    $("#btnSubmit").disabled = true;
    setSubmitFrozen(true);
    $("#aMeta").textContent = t("submitted");

    wsSend({
        type: "submit_answer",
        sessionId: state.sessionId,
        playerId: state.playerId,
        roundId: state.roundId,
        commandId: newCommandId(),
        nonceToken: state.nonceToken,
        answer,
        clientSentAtEpochMs: Date.now(),
    });
}

export function goNext() {
    resetRoundUi();
    startRound();
}

// re-export for bindUi convenience
export { applyFreeAnswerMode };
