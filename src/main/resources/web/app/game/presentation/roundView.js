import { $ } from "../../core/dom.js";
import { t } from "../../core/i18n.js";
import { escapeMarkdownInline, renderMarkdownMath } from "../../core/markdown.js";
import { escapeHtml, fmtMs, fmtScore, isChoiceCompact, isMobileLayout } from "../../core/utils.js";
import { renderModelBadgesHtml } from "../../ui/badges.js";
import { installRichTooltip } from "../../ui/tooltips.js";
import { updatePeekButtonNow, resetPeekButtonPosition } from "../../ui/peekButtonFollower.js";
import { GamePhase } from "../domain/gameState.js";

const totalRoundNumber = 5;

function renderBottomState(which) {
    const pre = $("#preRound");
    const preHint = $("#preRoundHint");
    const answer = $("#answerForm");
    const post = $("#postRound");

    const btnNext = $("#btnNext");
    const answerAction = $("#answerForm .bottom-action");
    const postAction = $("#postRound .bottom-action");

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

        if (resultDetails && answerSummary) {
            if (scratchToggle && scratchToggle.parentElement === answerSummary) {
                answerSummary.insertBefore(resultDetails, scratchToggle);
            } else {
                answerSummary.appendChild(resultDetails);
            }
        }

        wbr?.classList.add("hidden");
        return;
    }

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
}

function clearOutcomeGlows() {
    const remove = (el) => el?.classList.remove("glow-win", "glow-lose", "glow-tie");
    remove($("#bottomPanel"));
    remove($("#humanChip"));
    remove($("#llmChip"));
    remove($("#llmPanel"));
    remove($("#llmStatus"));
}

function applyOutcomeGlows(winner) {
    clearOutcomeGlows();

    const humanElements = [$("#bottomPanel"), $("#humanChip")];
    const llmElements = [$("#llmPanel"), $("#llmChip"), $("#llmStatus")];

    let humanGlowClass;
    let llmGlowClass;

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
            return;
    }

    humanElements.forEach((el) => el?.classList.add(humanGlowClass));
    llmElements.forEach((el) => el?.classList.add(llmGlowClass));
}

function formatAnswerDisplay(ans, choicesMaybe) {
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

function renderResultDetails({ note, detailLines }) {
    const el = $("#resultDetails");
    if (!el) return;

    const safeNote = String(note || "");
    const details = Array.isArray(detailLines) ? detailLines.map((x) => String(x || "")) : [];

    if (isMobileLayout()) {
        const parts = [];
        if (safeNote) {
            parts.push(`<div class="result-note">${escapeHtml(safeNote)}</div>`);
        }
        if (details.length) {
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

function roundResolveLine(reason) {
    const r = String(reason || "");
    if (r === "TIMEOVER_HUMAN") return t("resolveTimeUpYou");
    if (r === "TIMEOVER_LLM") return t("resolveTimeUpOpp");
    if (r === "TIMEOVER_BOTH") return t("resolveTimeUpBoth");
    return "";
}

export function createRoundView({ actions }) {
    const ac = new AbortController();
    const signal = ac.signal;
    const viewState = {
        reasoningPinnedToTop: true,
        programmaticScroll: false,
        reasoningRenderHandle: null,
        pendingReasoningText: "",
        reasoningSquaresShown: false,
        lastRoundId: null,
        lastQuestionKey: "",
        lastResultKey: "",
        lastResultDetails: null,
    };

    const llmChipAcc = $("#llmChip");
    if (llmChipAcc) {
        installRichTooltip(llmChipAcc, signal);
    }

    const choicesHost = $("#choicesHost");
    choicesHost?.addEventListener(
        "click",
        (event) => {
            const btn = event.target?.closest?.(".choice-btn");
            if (!btn) return;
            const idx = Number(btn.dataset.index);
            actions.selectChoice(idx);
        },
        { signal }
    );

    const llmScroll = $("#llmPanel .panel-body");
    if (llmScroll) {
        llmScroll.addEventListener(
            "scroll",
            () => {
                if (viewState.programmaticScroll) return;
                viewState.reasoningPinnedToTop = llmScroll.scrollTop <= 2;
            },
            { passive: true, signal }
        );
    }

    window.addEventListener(
        "resize",
        () => {
            if (!viewState.lastResultDetails) return;
            renderResultDetails(viewState.lastResultDetails);
        },
        { passive: true, signal }
    );

    function maybePinReasoningToTop() {
        const sc = $("#llmPanel .panel-body");
        if (!sc) return;
        if (!viewState.reasoningPinnedToTop) return;
        viewState.programmaticScroll = true;
        sc.scrollTop = 0;
        requestAnimationFrame(() => {
            viewState.programmaticScroll = false;
        });
    }

    function scrollLlmPanelToBottom() {
        const sc = $("#llmPanel .panel-body");
        if (!sc) return;
        viewState.programmaticScroll = true;
        sc.scrollTop = sc.scrollHeight;
        requestAnimationFrame(() => {
            sc.scrollTop = sc.scrollHeight;
            viewState.programmaticScroll = false;
        });
    }

    function scheduleReasoningRender(text) {
        viewState.pendingReasoningText = text;
        if (viewState.reasoningRenderHandle) return;
        viewState.reasoningRenderHandle = setTimeout(() => {
            viewState.reasoningRenderHandle = null;
            renderMarkdownMath(viewState.pendingReasoningText || "", $("#reasoningBody"));
            maybePinReasoningToTop();
            updatePeekButtonNow();
        }, 90);
    }

    function maybeAppendHiddenSquares(state) {
        if (state.llm.reasoningRevealed) return state.llm.reasoningBuf;

        const blocks = "██████████████████████████ \n".repeat(16);
        const withBlocks = (buf) => `${buf}\n\n\`${blocks}\``;

        if (viewState.reasoningSquaresShown) return withBlocks(state.llm.reasoningBuf);
        const canShowHint =
            state.llm.reasoningEnded || state.llm.status === "ANSWERING" || state.llm.status === "LOCKIN";
        if (!canShowHint) return state.llm.reasoningBuf;

        const since = Date.now() - (state.llm.lastReasoningAt || 0);
        if (since < 700) return state.llm.reasoningBuf;

        viewState.reasoningSquaresShown = true;
        return withBlocks(state.llm.reasoningBuf);
    }

    function renderMatchup(state) {
        $("#humanName").textContent = state.player.nickname || t("yourId");
        $("#humanSub").textContent = "";

        $("#llmName").textContent = state.session.opponentDisplayName || "LLM";

        const llmSub = $("#llmChip .chip-sub");
        if (llmSub) {
            llmSub.innerHTML = renderModelBadgesHtml({
                questionSetDisplayName: state.session.opponentQuestionSetDisplayName,
                questionSetDescription: state.session.opponentQuestionSetDescription,
                questionSetDescriptionI18nKey: state.session.opponentQuestionSetDescriptionI18nKey,
                difficulty: state.session.opponentDifficulty,
            });
        }

        const statusEl = $("#llmStatus");
        if (state.llm.status === "IDLE") statusEl.textContent = t("statusIdle");
        if (state.llm.status === "THINKING") statusEl.textContent = t("statusThinking");
        if (state.llm.status === "ANSWERING") statusEl.textContent = t("statusAnswering");
        if (state.llm.status === "LOCKIN") statusEl.textContent = t("statusLockin");
    }

    function renderTopTab(state) {
        const root = $("#splitTop");
        if (!root) return;
        root.classList.toggle("show-question", state.ui.topTab === "question");
        root.classList.toggle("show-reasoning", state.ui.topTab === "reasoning");
        $("#btnTopQuestion")?.classList.toggle("is-active", state.ui.topTab === "question");
        $("#btnTopReasoning")?.classList.toggle("is-active", state.ui.topTab === "reasoning");
    }

    function renderTimers(state) {
        const now = state.timers.now || Date.now();
        const total = Math.max(1, state.round.deadlineAt - state.round.releasedAt);
        const left = Math.max(0, state.round.deadlineAt - now);
        const inRound = state.phase === GamePhase.IN_ROUND;
        $("#deadlineLeft").textContent = inRound ? fmtMs(left) : "--:--";
        const pct = inRound ? Math.max(0, Math.min(1, left / total)) : 0;
        $("#deadlineBar").style.width = `${pct * 100}%`;

        const hsTotal = Math.max(1, state.round.handicapMs);
        const hsLeft = Math.max(0, state.round.releasedAt + state.round.handicapMs - now);
        $("#handicapLeft").textContent = inRound ? `${Math.ceil(hsLeft / 100) / 10}s` : "--";
        const hsPct = inRound ? Math.max(0, Math.min(1, hsLeft / hsTotal)) : 0;
        $("#handicapBar").style.width = `${hsPct * 100}%`;
    }

    function renderQuestion(state) {
        const choices = state.round.choices;
        const questionKey = `${state.round.id || ""}:${state.round.questionId || ""}:${choices ? choices.join("|") : ""}`;
        if (questionKey !== viewState.lastQuestionKey) {
            viewState.lastQuestionKey = questionKey;
            renderMarkdownMath(state.round.prompt || "", $("#questionBody"));

            const rn = state.round.number ? `#${state.round.number}` : "";
            $("#qMeta").textContent = [rn, state.round.questionId || ""].filter(Boolean).join("  ");

            const hasChoices = Array.isArray(choices) && choices.length;
            $("#qSep")?.classList.toggle("hidden", !hasChoices);
            $("#qSep2")?.classList.toggle("hidden", !hasChoices);
            $("#choiceHintTop")?.classList.toggle("hidden", !hasChoices);
            $("#choiceHintBottom")?.classList.toggle("hidden", !hasChoices);

            const host = $("#choicesHost");
            host.innerHTML = "";
            host.classList.remove("dense");

            if (hasChoices) {
                const dense = choices.every(isChoiceCompact);
                host.classList.toggle("dense", dense);

                for (let i = 0; i < choices.length; i++) {
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
                    renderMarkdownMath(choices[i], inner);
                    btn.appendChild(inner);
                    host.appendChild(btn);
                }

                $("#freeAnswerType").classList.add("hidden");
                $("#freeTextWrap").classList.add("hidden");
                $("#intWrap").classList.add("hidden");
            } else {
                $("#choicesHost").innerHTML = "";
                $("#freeAnswerType").classList.remove("hidden");
            }
        }

        document.querySelectorAll(".choice-btn").forEach((b) => {
            const idx = Number(b.dataset.index);
            b.classList.toggle("is-selected", idx === state.round.selectedChoiceIndex);
        });
    }

    function renderRoundDisplay(state) {
        const rd = $("#roundDisplay");
        if (!rd) return;
        const shouldShow =
            state.round.number > 0 &&
            state.phase !== GamePhase.SESSION_RESOLVED &&
            !state.ui.roundDisplayHidden;
        rd.textContent = shouldShow ? `${state.round.number}/${totalRoundNumber}` : "";
        rd.classList.toggle("hidden", !shouldShow);
    }

    function renderFreeAnswerMode(state) {
        const hasChoices = Array.isArray(state.round.choices) && state.round.choices.length;
        $("#freeAnswerType").classList.toggle("hidden", hasChoices);
        if (hasChoices) {
            $("#freeTextWrap").classList.add("hidden");
            $("#intWrap").classList.add("hidden");
            return;
        }
        $("#segText").classList.toggle("is-active", state.round.freeAnswerMode === "text");
        $("#segInt").classList.toggle("is-active", state.round.freeAnswerMode === "int");

        $("#freeTextWrap").classList.toggle("hidden", state.round.freeAnswerMode !== "text");
        $("#intWrap").classList.toggle("hidden", state.round.freeAnswerMode !== "int");
    }

    function renderLlmReasoning(state) {
        $("#omitHint").classList.toggle("hidden", !state.llm.reasoningTruncated);
        const masked = !(state.llm.reasoningRevealed || state.ui.reasoningPeeked);
        $("#reasoningWrap").classList.toggle("masked", masked);

        const reasoningText = maybeAppendHiddenSquares(state);
        scheduleReasoningRender(reasoningText);

        const streamError = $("#llmStreamError");
        if (streamError) {
            streamError.textContent = state.llm.streamError || "";
            streamError.classList.toggle("hidden", !state.llm.streamError);
        }
    }

    function renderLlmAnswer(state) {
        const shouldShow =
            state.llm.finalAnswer &&
            (state.phase !== GamePhase.IN_ROUND || state.round.submitted);
        $("#llmAnswerBox").classList.toggle("hidden", !shouldShow);

        if (!shouldShow) return;

        const conf =
            state.llm.confidenceScore == null
                ? ""
                : `${t("confidence")}: ${(state.llm.confidenceScore * 100).toFixed(0)}%`;
        $("#lblConfidence").textContent = conf;

        const body = $("#llmAnswerBody");
        const fa = state.llm.finalAnswer;
        if (!fa) {
            renderMarkdownMath("_no answer_", body);
        } else if (fa.type === "multiple_choice") {
            const idx = fa.choiceIndex;
            const txt =
                Array.isArray(state.round.choices) && state.round.choices[idx] != null
                    ? state.round.choices[idx]
                    : `choice #${idx}`;
            renderMarkdownMath(`**${escapeMarkdownInline(txt)}**`, body);
        } else if (fa.type === "integer") {
            renderMarkdownMath(`**${fa.value}**`, body);
        } else if (fa.type === "free_text") {
            renderMarkdownMath(fa.text || "", body);
        } else {
            renderMarkdownMath("_(unknown answer type)_", body);
        }

        scrollLlmPanelToBottom();
    }

    function renderResolution(state) {
        const resolution = state.round.resolution;
        if (!resolution) return;

        applyOutcomeGlows(resolution.winner);

        const lines = [];
        const note = roundResolveLine(resolution.reason);

        const hMark = resolution.humanCorrect === true ? " ✅" : resolution.humanCorrect === false ? " ❌" : "";
        const lMark = resolution.llmCorrect === true ? " ✅" : resolution.llmCorrect === false ? " ❌" : "";

        if (resolution.humanScore != null || resolution.llmScore != null) {
            lines.push(
                `${t("roundScore", { n: state.round.number })}: ${fmtScore(resolution.humanScore)} - ${fmtScore(resolution.llmScore)}`
            );
        }

        lines.push(`${t("correctAnswerLabel")}: ${formatAnswerDisplay(resolution.correctAnswer, state.round.choices)}`);
        lines.push(
            `${t("yourAnswerLabel")}${hMark}: ${state.round.humanAnswer
                ? formatAnswerDisplay(state.round.humanAnswer, state.round.choices)
                : t("noAnswer")
            }`
        );
        lines.push(
            `${t("oppAnswerLabel")}${lMark}: ${state.llm.finalAnswer
                ? formatAnswerDisplay(state.llm.finalAnswer, state.round.choices)
                : resolution.reason === "TIMEOVER_LLM"
                    ? t("noAnswer")
                    : t("oppPending")
            }`
        );

        const resultKey = JSON.stringify({ note, lines });
        if (resultKey !== viewState.lastResultKey) {
            viewState.lastResultKey = resultKey;
            viewState.lastResultDetails = { note, detailLines: lines };
            renderResultDetails(viewState.lastResultDetails);
        }

        if (resolution.correctAnswer?.type === "multiple_choice" && Array.isArray(state.round.choices)) {
            const correct = resolution.correctAnswer.choiceIndex;
            document.querySelectorAll(".choice-btn").forEach((b) => {
                const idx = Number(b.dataset.index);
                b.classList.toggle("is-correct", idx === correct);
                if (state.round.humanAnswer?.type === "multiple_choice") {
                    b.classList.toggle(
                        "is-wrong",
                        idx === state.round.humanAnswer.choiceIndex && idx !== correct
                    );
                }
            });
        }
    }

    function renderBottomActions(state) {
        const inRound = state.phase === GamePhase.IN_ROUND;
        const inPost = state.phase === GamePhase.ROUND_RESOLVED || state.phase === GamePhase.SESSION_RESOLVED;
        const bottomState = inRound ? "answer" : inPost ? "post" : "pre";
        renderBottomState(bottomState);

        const btnNext = $("#btnNext");
        if (btnNext) {
            btnNext.disabled = !(inPost || state.phase === GamePhase.ROUND_RESOLVED);
            btnNext.textContent = state.phase === GamePhase.SESSION_RESOLVED ? t("backToLobby") : t("next");
        }

        const btnStart = $("#btnStartRound");
        if (btnStart) {
            const canStart =
                (state.phase === GamePhase.SESSION_READY || state.phase === GamePhase.ROUND_RESOLVED) &&
                state.connection.status === "open";
            btnStart.disabled = state.ui.roundStartPending || !canStart;
            btnStart.textContent = state.ui.roundStartPending ? t("startingRound") : t("startRound");
            btnStart.setAttribute("aria-busy", state.ui.roundStartPending ? "true" : "false");
        }

        const hint = $("#preRoundHint");
        if (hint) {
            hint.textContent = state.ui.roundStartPending ? t("preRoundStartingHint") : t("preRoundHint");
        }
    }

    function resetRoundUiIfNeeded(state) {
        if (state.round.id === viewState.lastRoundId) return;
        viewState.lastRoundId = state.round.id;
        viewState.reasoningSquaresShown = false;
        viewState.reasoningPinnedToTop = true;
        viewState.lastResultKey = "";
        viewState.lastResultDetails = null;
        viewState.pendingReasoningText = "";
        $("#omitHint")?.classList.add("hidden");
        $("#llmStreamError")?.classList.add("hidden");
        $("#llmAnswerBox")?.classList.add("hidden");
        $("#reasoningWrap")?.classList.add("masked");
        $("#reasoningBody").innerHTML = "";
        $("#llmAnswerBody").innerHTML = "";
        $("#questionBody").innerHTML = "";
        $("#qSep")?.classList.add("hidden");
        $("#qSep2")?.classList.add("hidden");
        $("#choiceHintTop")?.classList.add("hidden");
        $("#choiceHintBottom")?.classList.add("hidden");
        $("#choicesHost").innerHTML = "";
        const roundDisplay = $("#roundDisplay");
        if (roundDisplay) {
            roundDisplay.textContent = "";
            roundDisplay.classList.add("hidden");
        }
        resetPeekButtonPosition();
        clearOutcomeGlows();
    }

    return {
        render(state) {
            if (state.screen !== "GAME") return;
            resetRoundUiIfNeeded(state);
            renderMatchup(state);
            renderTopTab(state);
            renderTimers(state);
            renderBottomActions(state);
            renderRoundDisplay(state);
            renderQuestion(state);
            renderFreeAnswerMode(state);
            renderLlmReasoning(state);
            renderLlmAnswer(state);
            if (state.round.resolution) {
                renderResolution(state);
            }
        },
        dispose() {
            ac.abort();
            if (viewState.reasoningRenderHandle) {
                clearTimeout(viewState.reasoningRenderHandle);
                viewState.reasoningRenderHandle = null;
            }
        },
    };
}
