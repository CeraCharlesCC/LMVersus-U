import { $ } from "../core/dom.js";
import { t } from "../core/i18n.js";
import { toast } from "../ui/toast.js";
import { state } from "../core/state.js";
import { setNet } from "../ui/netIndicator.js";
import { isMatchEndVisible, showMatchEndModal } from "../ui/modals.js";
import { setGiveUpVisible, showGame, showLobby } from "./uiScreens.js";
import {
    applyOutcomeGlows,
    enforceDeadline,
    formatAnswerDisplay,
    maybeShowHiddenSquares,
    renderQuestion,
    renderResultDetails,
    resetRoundUi,
    scheduleReasoningRender,
    setSubmitFrozen,
    showBottomState,
    showLlmAnswerBox,
    startTimers,
    stopTimers,
    updateLlmStatusPill,
    updateMatchupUi,
    updateTimers,
} from "./roundUi.js";
// local helper used above to preserve exact formatting
import { fmtScore as formatScore } from "../core/utils.js";

function roundResolveLine(reason) {
    const r = String(reason || "");
    if (r === "TIMEOVER_HUMAN") return t("resolveTimeUpYou");
    if (r === "TIMEOVER_LLM") return t("resolveTimeUpOpp");
    if (r === "TIMEOVER_BOTH") return t("resolveTimeUpBoth");
    return "";
}

function sessionEndLine(reason) {
    const r = String(reason || "");
    if (r === "timeout") return t("sessionEndIdle");
    if (r === "max_lifespan") return t("sessionEndMax");
    if (r === "completed") return t("sessionEndCompleted");
    return t("sessionEndGeneric");
}

/** ---- Server event handler ---- */
export function handleServerEvent(msg, { closeWs }) {
    const type = msg.type;

    if (type === "session_error") {
        const code = String(msg.errorCode || "");
        if (code === "rate_limited") {
            toast(t("rateLimitedTitle"), msg.message || t("rateLimitedMsgNoTime"), "warn", 5200);
        } else if (code === "session_limit_exceeded") {
            toast(t("rateLimitedTitle"), msg.message || t("rateLimitedMsgNoTime"), "warn", 5200);
        } else {
            toast(t("toastError"), `${code}: ${msg.message || ""}`, "error");
        }
        setNet(false);
        return;
    }

    if (type === "session_resolved") {
        state.inRound = false;
        stopTimers();
        $("#btnSubmit").disabled = true;
        $("#btnStartRound").disabled = true;
        setGiveUpVisible(false);

        state.ui.sessionEnded = true;
        showBottomState("post");

        $("#btnNext").disabled = false;
        $("#btnNext").textContent = t("backToLobby");

        showMatchEndModal({
            sessionId: msg.sessionId,
            state: msg.state,
            reason: msg.reason,
            humanTotalScore: msg.humanTotalScore,
            llmTotalScore: msg.llmTotalScore,
            winner: msg.winner,
            roundsPlayed: msg.roundsPlayed,
            totalRounds: msg.totalRounds,
            durationMs: msg.durationMs,
        });
        return;
    }

    if (type === "session_joined") {
        setNet(true);
        if (state.ws?.__toastOnJoin) {
            toast(t("toastSession"), t("toastNetOk"));
            state.ws.__toastOnJoin = false;
        }
        state.sessionId = msg.sessionId;
        location.hash = `session=${encodeURIComponent(state.sessionId)}`;

        state.ui.sessionEnded = false;

        showGame();
        resetRoundUi();
        updateMatchupUi();
        return;
    }

    if (type === "player_joined") {
        if (msg.playerId === state.playerId) {
            state.players.human = { playerId: msg.playerId, nickname: msg.nickname };
        } else {
            state.players.llm = { playerId: msg.playerId, nickname: msg.nickname };
        }
        updateMatchupUi();
        return;
    }

    if (type === "round_started") {
        resetRoundUi();
        state.inRound = true;

        state.sessionId = msg.sessionId;
        state.roundId = msg.roundId;
        state.roundNumber = msg.roundNumber;
        state.questionId = msg.questionId;
        state.questionPrompt = msg.questionPrompt || "";
        state.choices = msg.choices ?? null;

        if (msg.expectedAnswerType === "integer") {
            state.freeAnswerMode = "int";
        } else if (msg.expectedAnswerType === "free_text") {
            state.freeAnswerMode = "text";
        }

        state.releasedAt = msg.releasedAtEpochMs || Date.now();
        state.handicapMs = msg.handicapMs || 0;
        state.deadlineAt = msg.deadlineAtEpochMs || state.releasedAt + 60000;
        state.nonceToken = msg.nonceToken;

        state.llmStatus = "IDLE";
        updateMatchupUi();

        renderQuestion();
        startTimers();
        updateTimers(Date.now());
        enforceDeadline(); // In case of clock skew, immediately lock if already past deadline
        return;
    }

    if (type === "llm_thinking") {
        if (msg.roundId !== state.roundId) return;
        state.llmStatus = "THINKING";
        updateLlmStatusPill();
        return;
    }

    if (type === "llm_reasoning_delta") {
        if (msg.roundId !== state.roundId) return;

        if (typeof msg.seq === "number" && msg.seq <= state.reasoningSeq) return;
        state.reasoningSeq = msg.seq ?? state.reasoningSeq;

        state.reasoningBuf += msg.deltaText || "";
        state.lastReasoningAt = Date.now();

        if (state.llmStatus === "IDLE") {
            state.llmStatus = "THINKING";
            updateLlmStatusPill();
        }

        scheduleReasoningRender();
        return;
    }

    if (type === "llm_reasoning_truncated") {
        if (msg.roundId !== state.roundId) return;
        state.reasoningTruncated = true;
        $("#omitHint").classList.remove("hidden");
        return;
    }

    if (type === "llm_reasoning_ended") {
        if (msg.roundId !== state.roundId) return;
        state.reasoningEnded = true;
        state.llmStatus = "ANSWERING";
        updateLlmStatusPill();
        maybeShowHiddenSquares();
        return;
    }

    if (type === "llm_answer_lock_in") {
        if (msg.roundId !== state.roundId) return;
        state.llmStatus = "LOCKIN";
        updateLlmStatusPill();
        return;
    }

    if (type === "llm_final_answer") {
        if (msg.roundId !== state.roundId) return;

        // Always store the answer data - we need it for round_resolved display
        state.finalAnswer = msg.finalAnswer || null;
        state.confidenceScore = typeof msg.confidenceScore === "number" ? msg.confidenceScore : null;
        state.reasoningSummary = msg.reasoningSummary || null;

        // Defer UI update if the round is still in progress and human hasn't submitted
        if (state.inRound && !state.submitted) {
            return;
        }

        // Display the answer box
        showLlmAnswerBox();
        return;
    }

    if (type === "llm_stream_error") {
        if (msg.roundId !== state.roundId) return;
        state.streamError = msg.message || "stream error";
        const box = $("#llmStreamError");
        box.textContent = state.streamError;
        box.classList.remove("hidden");
        return;
    }

    if (type === "round_resolved") {
        if (msg.roundId !== state.roundId) return;

        state.roundResolveReason = msg.reason || null;

        state.inRound = false;
        stopTimers();
        $("#btnSubmit").disabled = true;
        setSubmitFrozen(false);

        applyOutcomeGlows(msg.winner);

        showBottomState("post");
        $("#btnNext").disabled = false;

        // Show the LLM answer box if we have a stored answer that wasn't displayed yet
        if (state.finalAnswer) {
            showLlmAnswerBox();
        }

        // highlight correct choice if multiple-choice
        if (msg.correctAnswer?.type === "multiple_choice" && Array.isArray(state.choices)) {
            const correct = msg.correctAnswer.choiceIndex;
            document.querySelectorAll(".choice-btn").forEach((b) => {
                const idx = Number(b.dataset.index);
                if (idx === correct) b.classList.add("is-correct");
                if (state.humanAnswer?.type === "multiple_choice" && idx === state.humanAnswer.choiceIndex && idx !== correct) {
                    b.classList.add("is-wrong");
                }
            });
        }

        const lines = [];
        const note = roundResolveLine(msg.reason);

        const hMark = msg.humanCorrect === true ? " ✅" : msg.humanCorrect === false ? " ❌" : "";
        const lMark = msg.llmCorrect === true ? " ✅" : msg.llmCorrect === false ? " ❌" : "";

        if (msg.humanScore != null || msg.llmScore != null) {
            lines.push(`${t("roundScore")}: ${String(msg.humanScore ?? "-")} - ${String(msg.llmScore ?? "-")}`.replace(/-?(\d+(\.\d+)?)/g, (m) => m));
            // keep exact display logic elsewhere; resultDetails already uses fmtScore in your original,
            // but leaving this line as-is would be a regression. So we keep original formatting below:
            lines.pop();
            lines.push(`${t("roundScore")}: ${formatScore(msg.humanScore)} - ${formatScore(msg.llmScore)}`);
        }

        lines.push(`${t("correctAnswerLabel")}: ${formatAnswerDisplay(msg.correctAnswer, state.choices)}`);
        lines.push(`${t("yourAnswerLabel")}${hMark}: ${state.humanAnswer ? formatAnswerDisplay(state.humanAnswer, state.choices) : t("noAnswer")}`);
        lines.push(
            `${t("oppAnswerLabel")}${lMark}: ${state.finalAnswer
                ? formatAnswerDisplay(state.finalAnswer, state.choices)
                : msg.reason === "TIMEOVER_LLM"
                    ? t("noAnswer")
                    : t("oppPending")
            }`
        );

        renderResultDetails(note, lines);
        return;
    }

    if (type === "llm_reasoning_reveal") {
        if (msg.roundId !== state.roundId) return;
        state.reasoningRevealed = true;
        state.reasoningBuf = msg.fullReasoning || "";
        scheduleReasoningRender();
        $("#reasoningWrap").classList.remove("masked");
        return;
    }

    if (type === "session_terminated") {
        if (state.ui.sessionEnded || isMatchEndVisible()) {
            closeWs();
        } else {
            const line = sessionEndLine(msg.reason || "");
            toast(t("toastSession"), line);
            closeWs();
            showLobby();
            resetRoundUi();
        }
    }
}

