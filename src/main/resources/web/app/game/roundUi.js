import {$} from "../core/dom.js";
import {t} from "../core/i18n.js";
import {escapeMarkdownInline, renderMarkdownMath} from "../core/markdown.js";
import {escapeHtml, fmtMs, isChoiceCompact, isMobileLayout,} from "../core/utils.js";
import {state} from "../core/state.js";
import {applySubmitLockState, resetWorkspace, updateAnswerSummary, updateKeyboardHint,} from "./workspace.js";
import {renderModelBadgesHtml} from "../ui/badges.js";
import {installRichTooltip} from "../ui/tooltips.js";

// Initialize rich tooltips for the LLM chip area (where badges appear)
const llmChipAcc = $("#llmChip");
if (llmChipAcc) {
    // No specific abort signal needed as this persists for the app lifetime
    installRichTooltip(llmChipAcc);
}


/** ---- Small UI helpers ---- */
export function showBottomState(which /* "pre" | "answer" | "post" */) {
    const pre = $("#preRound");
    const preHint = $("#preRoundHint");
    const answer = $("#answerForm");
    const post = $("#postRound");

    const btnNext = $("#btnNext");
    const answerAction = $("#answerForm .bottom-action");
    const postAction = $("#postRound .bottom-action");

    // Dynamic placement for result details (to show in answer form during post-round)
    const resultDetails = $("#resultDetails");
    const scratchToggle = $("#scratchpadToggle");
    const answerSummary = $("#answerSummary");
    const originalPostContent = $("#postRound .bottom-content");
    const wbr = $("#answerSummaryBreak");

    const moveNextTo = (target) => {
        if (!btnNext || !target) return;
        if (btnNext.parentElement !== target) target.appendChild(btnNext);
    };

    if (which === "post") {
        pre?.classList.add("hidden");
        preHint?.classList.add("hidden");
        post?.classList.add("hidden");

        answer?.classList.remove("hidden");
        answer?.classList.add("scratch-only");

        moveNextTo(answerAction);

        // Move resultDetails to sit above the scratchpad expand button in the summary area
        if (resultDetails && answerSummary) {
            if (scratchToggle && scratchToggle.parentElement === answerSummary) {
                answerSummary.insertBefore(resultDetails, scratchToggle);
            } else {
                answerSummary.appendChild(resultDetails);
            }
        }

        wbr?.classList.add("hidden");

        // Ensure kbdHint clears immediately when entering post state
        updateKeyboardHint();
        return;
    }

    // Reset resultDetails position for other states (so it doesn't appear in answer form)
    if (resultDetails && originalPostContent && resultDetails.parentElement !== originalPostContent) {
        originalPostContent.appendChild(resultDetails);
    }

    wbr?.classList.remove("hidden");

    answer?.classList.remove("scratch-only");
    moveNextTo(postAction);

    pre?.classList.toggle("hidden", which !== "pre");
    preHint?.classList.toggle("hidden", which !== "pre");
    answer?.classList.toggle("hidden", which !== "answer");
    post?.classList.toggle("hidden", which !== "post");

    // Keep keyboard hint consistent when switching states
    updateKeyboardHint();
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
    state.ui.lastResultDetails = {note: safeNote, details};

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
    $("#btnSubmit").classList.remove("is-locked");
    $("#btnStartRound").disabled = false;
    $("#btnNext").disabled = false;
    setSubmitFrozen(false);

    updateLlmStatusPill();
    stopTimers();
    updateTimers(0);

    // Reset workspace UI (summary chip, lock state, etc.)
    resetWorkspace();

    const bn = $("#btnNext");
    if (bn) bn.textContent = t("next");
}

export function updateMatchupUi() {
    $("#humanName").textContent = state.nickname || t("yourId");
    $("#humanSub").textContent = "";

    $("#llmName").textContent = state.opponentDisplayName || "LLM";

    // badges in the chip subtitle
    const llmSub = $("#llmChip .chip-sub");
    if (llmSub) {
        llmSub.innerHTML = renderModelBadgesHtml({
            questionSetDisplayName: state.opponentQuestionSetDisplayName,
            questionSetDescription: state.opponentQuestionSetDescription,
            questionSetDescriptionI18nKey: state.opponentQuestionSetDescriptionI18nKey,
            difficulty: state.opponentDifficulty,
        });
    }

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
    // Update workspace UI
    updateAnswerSummary();
    applySubmitLockState();
}

export function applyFreeAnswerMode() {
    $("#segText").classList.toggle("is-active", state.freeAnswerMode === "text");
    $("#segInt").classList.toggle("is-active", state.freeAnswerMode === "int");

    $("#freeTextWrap").classList.toggle("hidden", state.freeAnswerMode !== "text");
    $("#intWrap").classList.toggle("hidden", state.freeAnswerMode !== "int");

    // Update workspace to reflect mode change
    updateAnswerSummary();
    applySubmitLockState();
    updateKeyboardHint();
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

            const idxSpan = document.createElement("span");
            idxSpan.className = "choice-idx";
            idxSpan.textContent = `${i + 1}`;
            btn.appendChild(idxSpan);

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
        updateKeyboardHint();
        applySubmitLockState();
    } else {
        // free response
        $("#choicesHost").innerHTML = "";
        showBottomState("answer");
        $("#btnSubmit").disabled = false;
        $("#aMeta").textContent = "";

        $("#freeAnswerType").classList.remove("hidden");
        applyFreeAnswerMode();
        updateKeyboardHint();
        updateAnswerSummary();
        applySubmitLockState();
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
