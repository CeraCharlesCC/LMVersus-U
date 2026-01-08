import { $ } from "../core/dom.js";
import { t } from "../core/i18n.js";
import { renderMarkdownMath, escapeMarkdownInline } from "../core/markdown.js";
import {
    fmtMs,
    isMobileLayout,
    escapeHtml,
    normalizeChoiceForHeuristic,
    isChoiceCompact,
    fmtScore,
} from "../core/utils.js";
import { state } from "../core/state.js";

/** ---- Small UI helpers ---- */
export function showBottomState(which /* "pre" | "answer" | "post" */) {
    $("#preRound")?.classList.toggle("hidden", which !== "pre");
    $("#preRoundHint")?.classList.toggle("hidden", which !== "pre");
    $("#answerForm")?.classList.toggle("hidden", which !== "answer");
    $("#postRound")?.classList.toggle("hidden", which !== "post");
}

function clearOutcomeGlows() {
    const remove = (el) => el?.classList.remove("glow-win", "glow-lose", "glow-tie");
    remove($("#bottomPanel"));
    remove($("#humanChip"));
    remove($("#llmChip"));
    remove($("#llmPanel"));
    remove($("#llmStatus"));
}

export function applyOutcomeGlows(winner) {
    clearOutcomeGlows();

    const humanElements = [$("#bottomPanel"), $("#humanChip")];
    const llmElements = [$("#llmPanel"), $("#llmChip"), $("#llmStatus")];

    let humanGlowClass, llmGlowClass;

    switch (winner) {
        case "HUMAN":
            humanGlowClass = "glow-win";
            llmGlowClass = "glow-lose";
            break;
        case "LLM":
            humanGlowClass = "glow-lose";
            llmGlowClass = "glow-win";
            break;
        case "TIE":
            humanGlowClass = "glow-tie";
            llmGlowClass = "glow-tie";
            break;
        default:
            return; // No glow for other cases
    }

    humanElements.forEach((el) => el?.classList.add(humanGlowClass));
    llmElements.forEach((el) => el?.classList.add(llmGlowClass));
}

export function setSubmitFrozen(on) {
    const btn = $("#btnSubmit");
    if (!btn) return;
    btn.classList.toggle("is-frozen", !!on);
}

export function getLlmScrollEl() {
    return $("#llmPanel .panel-body");
}

function maybePinReasoningToTop() {
    const sc = getLlmScrollEl();
    if (!sc) return;
    if (!state.ui.reasoningPinnedToTop) return;

    state.ui.programmaticScroll = true;
    sc.scrollTop = 0;
    requestAnimationFrame(() => {
        state.ui.programmaticScroll = false;
    });
}

export function scrollLlmPanelToBottom() {
    const sc = $("#llmPanel .panel-body");
    if (!sc) return;

    state.ui.programmaticScroll = true;
    sc.scrollTop = sc.scrollHeight;

    requestAnimationFrame(() => {
        sc.scrollTop = sc.scrollHeight;
        state.ui.programmaticScroll = false;
    });
}

export function renderResultDetails(note, detailLines) {
    const el = $("#resultDetails");
    if (!el) return;

    const safeNote = String(note || "");
    const details = Array.isArray(detailLines) ? detailLines.map((x) => String(x || "")) : [];

    // cache so we can re-render on resize/orientation change
    state.ui.lastResultDetails = { note: safeNote, details };

    if (isMobileLayout()) {
        const parts = [];
        if (safeNote) {
            parts.push(`<div class="result-note">${escapeHtml(safeNote)}</div>`);
        }
        if (details.length) {
            // Prefer a single horizontal line; allow wrap that starts at the slash via <wbr>
            const joined = details.map(escapeHtml).join(" <wbr>/ ");
            parts.push(`<div class="result-inline">${joined}</div>`);
        }
        el.innerHTML = parts.join("");
        return;
    }

    const lines = [];
    if (safeNote) lines.push(safeNote);
    lines.push(...details);
    el.textContent = lines.join("\n");
}

export function resetRoundUi() {
    state.inRound = false;
    state.roundId = null;
    state.nonceToken = null;
    state.questionId = null;
    state.questionPrompt = "";
    state.choices = null;
    state.releasedAt = 0;
    state.handicapMs = 0;
    state.deadlineAt = 0;

    state.selectedChoiceIndex = null;
    state.submitted = false;
    state.humanAnswer = null;

    state.llmStatus = "IDLE";
    state.reasoningBuf = "";
    state.reasoningSeq = -1;
    state.reasoningEnded = false;
    state.reasoningSquaresShown = false;
    state.reasoningTruncated = false;
    state.lastReasoningAt = 0;
    state.finalAnswer = null;
    state.confidenceScore = null;
    state.reasoningSummary = null;
    state.streamError = null;
    state.roundResolveReason = null;
    state.freeAnswerMode = "text";
    state.reasoningRevealed = false;

    state.ui.reasoningPinnedToTop = true;
    const sc = getLlmScrollEl();
    if (sc) sc.scrollTop = 0;

    clearOutcomeGlows();

    state.ui.lastResultDetails = null;
    $("#omitHint").classList.add("hidden");
    $("#llmStreamError").classList.add("hidden");
    $("#llmAnswerBox").classList.add("hidden");

    $("#reasoningWrap").classList.add("masked");
    $("#reasoningBody").innerHTML = "";
    $("#llmAnswerBody").innerHTML = "";

    $("#questionBody").innerHTML = "";
    $("#qSep")?.classList.add("hidden");
    $("#qSep2")?.classList.add("hidden");
    $("#choiceHintTop")?.classList.add("hidden");
    $("#choiceHintBottom")?.classList.add("hidden");
    $("#choicesHost").innerHTML = "";

    showBottomState("pre");

    $("#aMeta").textContent = "";
    $("#qMeta").textContent = "";

    // Re-enable buttons in case a previous session disabled them
    $("#btnSubmit").disabled = false;
    $("#btnStartRound").disabled = false;
    $("#btnNext").disabled = false;
    setSubmitFrozen(false);

    updateLlmStatusPill();
    stopTimers();
    updateTimers(0);
}

export function updateMatchupUi() {
    $("#humanName").textContent = state.nickname || t("yourId");
    // UX: don’t show internal IDs in the game header
    $("#humanSub").textContent = "";

    $("#llmName").textContent = state.opponentDisplayName || "LLM";
    updateLlmStatusPill();
}

export function updateLlmStatusPill() {
    const el = $("#llmStatus");
    if (state.llmStatus === "IDLE") el.textContent = t("statusIdle");
    if (state.llmStatus === "THINKING") el.textContent = t("statusThinking");
    if (state.llmStatus === "ANSWERING") el.textContent = t("statusAnswering");
    if (state.llmStatus === "LOCKIN") el.textContent = t("statusLockin");
}

export function startTimers() {
    stopTimers();
    state.timers.tickHandle = setInterval(() => {
        updateTimers(Date.now());
        enforceDeadline();
    }, 100);
}

export function stopTimers() {
    if (state.timers.tickHandle) clearInterval(state.timers.tickHandle);
    state.timers.tickHandle = null;
}

export function updateTimers(now = Date.now()) {
    const total = Math.max(1, state.deadlineAt - state.releasedAt);
    const left = Math.max(0, state.deadlineAt - now);
    $("#deadlineLeft").textContent = state.inRound ? fmtMs(left) : "--:--";
    const pct = state.inRound ? Math.max(0, Math.min(1, left / total)) : 0;
    $("#deadlineBar").style.width = `${pct * 100}%`;

    const hsTotal = Math.max(1, state.handicapMs);
    const hsLeft = Math.max(0, state.releasedAt + state.handicapMs - now);
    $("#handicapLeft").textContent = state.inRound ? `${Math.ceil(hsLeft / 100) / 10}s` : "--";
    const hsPct = state.inRound ? Math.max(0, Math.min(1, hsLeft / hsTotal)) : 0;
    $("#handicapBar").style.width = `${hsPct * 100}%`;
}

export function enforceDeadline() {
    if (!state.inRound) return;
    if (Date.now() <= state.deadlineAt) return;
    $("#btnSubmit").disabled = true;
    $("#aMeta").textContent = t("timeUp");
}

export function setTopMobileTab(which) {
    const root = $("#splitTop");
    root.classList.toggle("show-question", which === "question");
    root.classList.toggle("show-reasoning", which === "reasoning");

    $("#btnTopQuestion").classList.toggle("is-active", which === "question");
    $("#btnTopReasoning").classList.toggle("is-active", which === "reasoning");
}

function updateChoiceSelection() {
    document.querySelectorAll(".choice-btn").forEach((b) => {
        const idx = Number(b.dataset.index);
        b.classList.toggle("is-selected", idx === state.selectedChoiceIndex);
    });
}

export function applyFreeAnswerMode() {
    $("#segText").classList.toggle("is-active", state.freeAnswerMode === "text");
    $("#segInt").classList.toggle("is-active", state.freeAnswerMode === "int");

    $("#freeTextWrap").classList.toggle("hidden", state.freeAnswerMode !== "text");
    $("#intWrap").classList.toggle("hidden", state.freeAnswerMode !== "int");
}

export function renderQuestion() {
    renderMarkdownMath(state.questionPrompt || "", $("#questionBody"));

    const rn = state.roundNumber ? `#${state.roundNumber}` : "";
    $("#qMeta").textContent = [rn, state.questionId || ""].filter(Boolean).join("  ");

    const hasChoices = Array.isArray(state.choices) && state.choices.length;
    $("#qSep")?.classList.toggle("hidden", !hasChoices);
    $("#qSep2")?.classList.toggle("hidden", !hasChoices);
    $("#choiceHintTop")?.classList.toggle("hidden", !hasChoices);
    $("#choiceHintBottom")?.classList.toggle("hidden", !hasChoices);

    const host = $("#choicesHost");
    host.innerHTML = "";
    host.classList.remove("dense");

    if (hasChoices) {
        const dense = state.choices.every(isChoiceCompact);
        host.classList.toggle("dense", dense);

        for (let i = 0; i < state.choices.length; i++) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "choice-btn";
            btn.dataset.index = String(i);

            const inner = document.createElement("div");
            inner.className = "choice-inner";
            renderMarkdownMath(state.choices[i], inner);
            btn.appendChild(inner);

            btn.addEventListener("click", () => {
                if (!state.inRound || state.submitted) return;
                state.selectedChoiceIndex = i;
                updateChoiceSelection();
            });

            host.appendChild(btn);
        }

        $("#freeAnswerType").classList.add("hidden");
        $("#freeTextWrap").classList.add("hidden");
        $("#intWrap").classList.add("hidden");

        showBottomState("answer");
        $("#btnSubmit").disabled = false;
        $("#aMeta").textContent = "";
        updateChoiceSelection();
    } else {
        // free response
        $("#choicesHost").innerHTML = "";
        showBottomState("answer");
        $("#btnSubmit").disabled = false;
        $("#aMeta").textContent = "";

        $("#freeAnswerType").classList.remove("hidden");
        applyFreeAnswerMode();
    }
}

export function scheduleReasoningRender() {
    if (state.timers.renderHandle) return;
    state.timers.renderHandle = setTimeout(() => {
        state.timers.renderHandle = null;
        renderMarkdownMath(state.reasoningBuf || "", $("#reasoningBody"));
        maybePinReasoningToTop();
    }, 90);
}

export function maybeShowHiddenSquares() {
    if (!state.reasoningEnded) return;
    if (state.reasoningSquaresShown) return;

    setTimeout(() => {
        if (!state.inRound) return;
        if (!state.reasoningEnded || state.reasoningSquaresShown) return;
        if (state.reasoningRevealed) return;

        const since = Date.now() - (state.lastReasoningAt || 0);
        if (since < 700) return;

        state.reasoningSquaresShown = true;
        const blocks = "██████████████████████████ \n".repeat(16);
        state.reasoningBuf += `\n\n\`${blocks}\``;
        scheduleReasoningRender();
    }, 900);
}

export function formatAnswerDisplay(ans, choicesMaybe) {
    if (!ans) return t("noAnswer");
    if (ans.type === "multiple_choice") {
        const idx = ans.choiceIndex;
        const choices = choicesMaybe;
        if (Array.isArray(choices) && choices[idx] != null) {
            const cleaned = normalizeChoiceForHeuristic(choices[idx]);
            if (cleaned) return `#${idx + 1} ${cleaned.length > 64 ? cleaned.slice(0, 64) + "…" : cleaned}`;
        }
        return `#${idx + 1}`;
    }
    if (ans.type === "integer") return String(ans.value);
    if (ans.type === "free_text") {
        const text = String(ans.text || "").trim();
        if (!text) return t("noAnswer");
        return text.length > 80 ? text.slice(0, 80) + "…" : text;
    }
    return t("noAnswer");
}

/** Helper to display the LLM answer box (extracted for reuse) */
export function showLlmAnswerBox() {
    $("#llmAnswerBox").classList.remove("hidden");

    const conf = state.confidenceScore == null ? "" : `${t("confidence")}: ${(state.confidenceScore * 100).toFixed(0)}%`;
    $("#lblConfidence").textContent = conf;

    const body = $("#llmAnswerBody");
    const fa = state.finalAnswer;

    if (!fa) {
        renderMarkdownMath("_no answer_", body);
    } else if (fa.type === "multiple_choice") {
        const idx = fa.choiceIndex;
        const txt = Array.isArray(state.choices) && state.choices[idx] != null ? state.choices[idx] : `choice #${idx}`;
        renderMarkdownMath(`**${escapeMarkdownInline(txt)}**`, body);
    } else if (fa.type === "integer") {
        renderMarkdownMath(`**${fa.value}**`, body);
    } else if (fa.type === "free_text") {
        renderMarkdownMath(fa.text || "", body);
    } else {
        renderMarkdownMath("_(unknown answer type)_", body);
    }

    state.ui.reasoningPinnedToTop = false;
    scrollLlmPanelToBottom();
}
