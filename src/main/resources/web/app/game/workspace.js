import { $ } from "../core/dom.js";
import { t } from "../core/i18n.js";
import { isMobileLayout } from "../core/utils.js";
import { toast } from "../ui/toast.js";
import { GamePhase } from "./domain/gameState.js";

function hasChoices(state) {
    return Array.isArray(state.round.choices) && state.round.choices.length > 0;
}

function deriveAnswerValidity(state, now) {
    if (state.phase !== GamePhase.IN_ROUND) return { valid: false, reason: null };
    if (state.round.submitted) return { valid: true, reason: null };
    if (now > state.round.deadlineAt && state.round.deadlineAt > 0) {
        return { valid: false, reason: t("timeUp") };
    }

    if (hasChoices(state)) {
        if (state.round.selectedChoiceIndex == null) {
            return { valid: false, reason: t("lockReasonMcq") };
        }
        return { valid: true, reason: null };
    }

    if (state.round.freeAnswerMode === "int") {
        const raw = String(state.round.answerInt || "").trim();
        if (!raw || !/^-?\d+$/.test(raw)) {
            return { valid: false, reason: t("lockReasonInt") };
        }
        return { valid: true, reason: null };
    }

    const text = String(state.round.answerText || "").trim();
    if (!text) {
        return { valid: false, reason: t("lockReasonText") };
    }
    return { valid: true, reason: null };
}

function formatCurrentAnswerSummary(state) {
    if (hasChoices(state)) {
        const idx = state.round.selectedChoiceIndex;
        if (idx == null) {
            return t("answerSummaryEmpty");
        }
        return t("answerSummaryMcq", { n: idx + 1 });
    }

    if (state.round.freeAnswerMode === "int") {
        const raw = String(state.round.answerInt || "").trim();
        if (!raw) return t("answerSummaryEmpty");
        return t("answerSummaryInt", { v: raw });
    }

    const text = String(state.round.answerText || "").trim();
    if (!text) return t("answerSummaryEmpty");
    const display = text.length > 50 ? text.slice(0, 50) + "…" : text;
    return t("answerSummaryText", { v: display });
}

function updateAnswerSummary(state) {
    const el = $("#answerSummaryText");
    if (!el) return;

    const summary = formatCurrentAnswerSummary(state);
    el.textContent = summary;

    let hasValue = false;
    if (hasChoices(state)) {
        hasValue = state.round.selectedChoiceIndex != null;
    } else if (state.round.freeAnswerMode === "int") {
        hasValue = !!String(state.round.answerInt || "").trim();
    } else {
        hasValue = !!String(state.round.answerText || "").trim();
    }

    el.classList.toggle("has-value", hasValue);
}

function updateKeyboardHint(state) {
    const el = $("#kbdHint");
    if (!el) return;

    if (isMobileLayout()) {
        el.textContent = "";
        return;
    }

    if (state.phase !== GamePhase.IN_ROUND) {
        el.textContent = "";
        return;
    }

    if (hasChoices(state)) {
        el.textContent = t("kbdHintMcq");
    } else if (state.round.freeAnswerMode === "int") {
        el.textContent = t("kbdHintInt");
    } else {
        el.textContent = t("kbdHintText");
    }
}

function updateSubmitLockState(state) {
    const btn = $("#btnSubmit");
    const meta = $("#aMeta");
    if (!btn) return;

    if (state.phase !== GamePhase.IN_ROUND) {
        btn.classList.remove("is-locked", "is-frozen");
        btn.disabled = true;
        if (meta) meta.textContent = "";
        return;
    }

    if (state.round.submitted) {
        btn.classList.remove("is-locked");
        btn.classList.add("is-frozen");
        btn.disabled = true;
        if (meta) meta.textContent = t("submitted");
        return;
    }

    btn.classList.remove("is-frozen");
    const now = state.timers.now || Date.now();
    const { valid, reason } = deriveAnswerValidity(state, now);

    if (!valid) {
        btn.disabled = true;
        btn.classList.add("is-locked");
        if (meta) meta.textContent = reason || "";
    } else {
        btn.disabled = false;
        btn.classList.remove("is-locked");
        if (meta) meta.textContent = "";
    }
}

function updateClearButtonState(state) {
    const btn = $("#btnClearAnswer");
    if (btn) {
        btn.disabled = !!state.round.submitted;
    }
}

function applyScratchpadPlacement() {
    const toggle = $("#scratchpadToggle");
    const panel = $("#scratchpadPanel");
    const section = $("#scratchpadSection");
    const summary = $("#answerSummary");

    if (!toggle || !panel || !section) return;

    if (isMobileLayout()) {
        if (toggle.parentElement !== summary) {
            summary.appendChild(toggle);
        }
        if (panel.parentElement !== section) {
            section.appendChild(panel);
        }
    } else {
        if (toggle.parentElement !== summary) {
            summary.appendChild(toggle);
        }
        if (panel.parentElement !== section) {
            section.appendChild(panel);
        }
    }
}

async function copyToClipboard(text) {
    try {
        if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
            await navigator.clipboard.writeText(text);
            return true;
        }
        const textarea = document.createElement("textarea");
        textarea.value = text;
        textarea.style.position = "fixed";
        textarea.style.left = "-9999px";
        document.body.appendChild(textarea);
        textarea.select();
        const ok = document.execCommand("copy");
        document.body.removeChild(textarea);
        return ok;
    } catch {
        return false;
    }
}

export function evalCalc(expr) {
    if (!expr || !expr.trim()) {
        return { value: null, error: null };
    }

    const sanitized = expr
        .toLowerCase()
        .replace(/\s+/g, "")
        .replace(/\bpi\b/g, `(${Math.PI})`)
        .replace(/\be\b/g, `(${Math.E})`)
        .replace(/\bsqrt\(/g, "Math.sqrt(")
        .replace(/\bpow\(/g, "Math.pow(")
        .replace(/\blog\(/g, "Math.log10(")
        .replace(/\bln\(/g, "Math.log(")
        .replace(/\bsin\(/g, "Math.sin(")
        .replace(/\bcos\(/g, "Math.cos(")
        .replace(/\btan\(/g, "Math.tan(")
        .replace(/\babs\(/g, "Math.abs(");

    const safePattern =
        /^(?:\d+(?:\.\d+)?|[+\-*/(),]|\bMath\.(?:sqrt|pow|log|log10|sin|cos|tan|abs)\b)+$/;
    if (!safePattern.test(sanitized)) {
        return { value: null, error: "Invalid expression" };
    }

    let depth = 0;
    for (const ch of sanitized) {
        if (ch === "(") depth++;
        if (ch === ")") depth--;
        if (depth < 0) {
            return { value: null, error: "Unbalanced parentheses" };
        }
    }
    if (depth !== 0) {
        return { value: null, error: "Unbalanced parentheses" };
    }

    try {
        const fn = new Function(`"use strict"; return (${sanitized});`);
        const result = fn();

        if (typeof result !== "number" || !Number.isFinite(result)) {
            return { value: null, error: "Invalid result" };
        }

        const rounded = Math.round(result * 1e10) / 1e10;
        return { value: rounded, error: null };
    } catch {
        return { value: null, error: "Eval error" };
    }
}

export function createWorkspaceView({ actions }) {
    const ac = new AbortController();
    const signal = ac.signal;
    let currentState = null;
    initWorkspaceText();

    $("#btnClearAnswer")?.addEventListener(
        "click",
        () => actions.clearAnswer(),
        { signal }
    );

    $("#scratchpadToggle")?.addEventListener(
        "click",
        () => actions.toggleScratchpad(),
        { signal }
    );

    $("#scratchpadText")?.addEventListener(
        "input",
        (event) => actions.updateScratchpadText(event.target.value),
        { signal }
    );

    $("#btnCalcEval")?.addEventListener(
        "click",
        () => runCalc(actions),
        { signal }
    );

    $("#calcExpr")?.addEventListener(
        "keydown",
        (event) => {
            if (event.key === "Enter") {
                event.preventDefault();
                runCalc(actions);
            }
        },
        { signal }
    );
    $("#calcExpr")?.addEventListener(
        "input",
        (event) => actions.updateCalcExpr(event.target.value),
        { signal }
    );

    $("#btnScratchClear")?.addEventListener(
        "click",
        () => actions.clearScratchpad(),
        { signal }
    );

    $("#freeText")?.addEventListener(
        "input",
        (event) => actions.updateFreeText(event.target.value),
        { signal }
    );

    $("#intValue")?.addEventListener(
        "input",
        (event) => actions.updateIntValue(event.target.value),
        { signal }
    );

    window.addEventListener(
        "keydown",
        (event) => handleKeyboardShortcut(event, actions, () => currentState),
        { signal }
    );

    let resizeRaf = 0;
    window.addEventListener(
        "resize",
        () => {
            if (resizeRaf) cancelAnimationFrame(resizeRaf);
            resizeRaf = requestAnimationFrame(() => {
                resizeRaf = 0;
                applyScratchpadPlacement();
            });
        },
        { passive: true, signal }
    );

    $("#btnCopyQuestion")?.addEventListener(
        "click",
        async () => {
            if (!currentState) return;
            const text = currentState.round.prompt || "";
            if (!text.trim()) return;
            const ok = await copyToClipboard(text);
            toast(ok ? t("copied") : t("copyFailed"), "", ok ? "info" : "error");
        },
        { signal }
    );

    $("#btnCopyChoice")?.addEventListener(
        "click",
        async () => {
            if (!currentState || !hasChoices(currentState)) return;
            const idx = currentState.round.selectedChoiceIndex;
            if (idx == null) return;
            const text = currentState.round.choices[idx] || "";
            if (!text.trim()) return;
            const ok = await copyToClipboard(text);
            toast(ok ? t("copied") : t("copyFailed"), "", ok ? "info" : "error");
        },
        { signal }
    );

    $("#btnCopyScratch")?.addEventListener(
        "click",
        async () => {
            if (!currentState) return;
            const text = currentState.ui.workspace.scratchText || "";
            if (!text.trim()) return;
            const ok = await copyToClipboard(text);
            toast(ok ? t("copied") : t("copyFailed"), "", ok ? "info" : "error");
        },
        { signal }
    );

    function renderScratchpad(state) {
        const open = !!state.ui.workspace.scratchOpen;
        const toggle = $("#scratchpadToggle");
        const panel = $("#scratchpadPanel");
        const arrow = $("#scratchpadArrow");

        if (toggle) {
            toggle.classList.toggle("is-open", open);
            toggle.setAttribute("aria-expanded", open ? "true" : "false");
        }
        if (panel) panel.classList.toggle("hidden", !open);
        if (arrow) arrow.textContent = open ? "▾" : "▸";

        const scratch = $("#scratchpadText");
        if (scratch && scratch.value !== state.ui.workspace.scratchText) {
            scratch.value = state.ui.workspace.scratchText || "";
        }

        const calcExpr = $("#calcExpr");
        if (calcExpr && calcExpr.value !== state.ui.workspace.calcExpr) {
            calcExpr.value = state.ui.workspace.calcExpr || "";
        }

        const resultEl = $("#calcResult");
        if (resultEl) {
            resultEl.textContent = state.ui.workspace.calcResult || "";
            const text = state.ui.workspace.calcResult || "";
            const isNumber = /^-?\d+(\.\d+)?$/.test(text.trim());
            resultEl.classList.toggle("error", !!text && !isNumber);
        }
    }

    return {
        render(state) {
            currentState = state;
            const freeText = $("#freeText");
            if (freeText && freeText.value !== state.round.answerText) {
                freeText.value = state.round.answerText || "";
            }
            const intValue = $("#intValue");
            if (intValue && intValue.value !== state.round.answerInt) {
                intValue.value = state.round.answerInt || "";
            }
            updateAnswerSummary(state);
            updateKeyboardHint(state);
            updateSubmitLockState(state);
            updateClearButtonState(state);
            applyScratchpadPlacement();
            renderScratchpad(state);
        },
        dispose() {
            ac.abort();
        },
    };
}

function initWorkspaceText() {
    const btn = (id, key) => {
        const el = $(id);
        if (el) el.textContent = t(key);
    };

    btn("#btnClearAnswer", "clearAnswer");
    btn("#scratchpadLabel", "scratchpad");
    btn("#btnScratchClear", "calcClear");

    const calcInput = $("#calcExpr");
    if (calcInput) calcInput.placeholder = t("calcPlaceholder");

    const scratch = $("#scratchpadText");
    if (scratch) scratch.placeholder = t("scratchpadPlaceholder");
}

function handleKeyboardShortcut(event, actions, getState) {
    const state = getState();
    if (!state) return;
    if (isMobileLayout()) return;
    if (state.phase !== GamePhase.IN_ROUND || state.round.submitted) return;
    if (!hasChoices(state)) return;
    const target = event.target;
    const tag = target?.tagName;
    const isTypingContext =
        !!target &&
        (target.isContentEditable || tag === "TEXTAREA" || tag === "INPUT" || tag === "SELECT");
    if (isTypingContext) return;

    const key = event.key;
    const m = event.code?.match(/^Digit([0-9])$/);
    if (m) {
        const d = Number(m[1]);
        let idx = d - 1;
        if (d === 0) idx = 9;
        if (event.shiftKey) idx += 10;
        if (idx < state.round.choices.length) {
            actions.selectChoice(idx);
            event.preventDefault();
        }
        return;
    }
    if (key === "Enter") {
        if (state.round.selectedChoiceIndex != null) {
            actions.submitAnswer();
            event.preventDefault();
        }
    }
}

function runCalc(actions) {
    const input = $("#calcExpr");
    const resultEl = $("#calcResult");
    if (!input || !resultEl) return;

    const expr = input.value || "";
    const { value, error } = evalCalc(expr);

    const resultText = error ? String(error) : value != null ? String(value) : "";
    const isErr = !!error;

    resultEl.textContent = resultText;
    resultEl.classList.toggle("error", isErr);

    actions.updateCalcExpr(expr);
    actions.updateCalcResult(resultText);
}
