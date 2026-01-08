/**
 * workspace.js - Bottom panel workspace features for in-round UI
 *
 * Features:
 * - Answer validity derivation and locked submit state
 * - Answer summary chip with clear action
 * - Copy utilities (question, choice, scratchpad)
 * - Collapsible scratchpad with calculator
 * - Keyboard shortcuts (PC only)
 */

import { $ } from "../core/dom.js";
import { t } from "../core/i18n.js";
import { state } from "../core/state.js";
import { isMobileLayout } from "../core/utils.js";
import { toast } from "../ui/toast.js";

// ---- State extensions (add to global state.ui) ----
const initWorkspaceState = () => {
    if (state.ui.workspace) return; // already initialized
    state.ui.workspace = {
        scratchOpen: !isMobileLayout(),
        scratchText: "",
        calcExpr: "",
        calcResult: "",
    };
};

// ---- Answer Validity ----

/**
 * Derive current answer validity.
 * @returns {{ valid: boolean, reason: string | null }}
 */
export function deriveAnswerValidity() {
    if (!state.inRound) return { valid: false, reason: null };
    if (state.submitted) return { valid: true, reason: null }; // already submitted

    const hasChoices = Array.isArray(state.choices) && state.choices.length > 0;

    if (hasChoices) {
        // MCQ mode
        if (state.selectedChoiceIndex == null) {
            return { valid: false, reason: t("lockReasonMcq") };
        }
        return { valid: true, reason: null };
    } else {
        // Free response mode
        if (state.freeAnswerMode === "int") {
            const raw = ($("#intValue")?.value || "").trim();
            if (!raw || !/^-?\d+$/.test(raw)) {
                return { valid: false, reason: t("lockReasonInt") };
            }
            return { valid: true, reason: null };
        } else {
            // text mode
            const text = ($("#freeText")?.value || "").trim();
            if (!text) {
                return { valid: false, reason: t("lockReasonText") };
            }
            return { valid: true, reason: null };
        }
    }
}

/**
 * Apply the locked/unlocked submit state based on answer validity.
 */
export function applySubmitLockState() {
    const btn = $("#btnSubmit");
    const meta = $("#aMeta");
    if (!btn) return;

    // Don't override frozen (already submitted) or time-up
    if (state.submitted) {
        btn.classList.remove("is-locked");
        return;
    }

    // Check if time is up
    if (Date.now() > state.deadlineAt && state.deadlineAt > 0) {
        btn.classList.remove("is-locked");
        btn.disabled = true;
        if (meta) meta.textContent = t("timeUp");
        return;
    }

    const { valid, reason } = deriveAnswerValidity();

    if (!valid) {
        btn.disabled = true;
        btn.classList.add("is-locked");
        if (meta && reason) meta.textContent = reason;
    } else {
        btn.disabled = false;
        btn.classList.remove("is-locked");
        if (meta) meta.textContent = "";
    }
}

// ---- Answer Summary Chip ----

/**
 * Format the current answer for the summary chip.
 * @returns {string}
 */
export function formatCurrentAnswerSummary() {
    const hasChoices = Array.isArray(state.choices) && state.choices.length > 0;

    if (hasChoices) {
        const idx = state.selectedChoiceIndex;
        if (idx == null) {
            return t("answerSummaryEmpty");
        }
        return t("answerSummaryMcq", { n: idx + 1 });
    } else {
        if (state.freeAnswerMode === "int") {
            const raw = ($("#intValue")?.value || "").trim();
            if (!raw) return t("answerSummaryEmpty");
            return t("answerSummaryInt", { v: raw });
        } else {
            const text = ($("#freeText")?.value || "").trim();
            if (!text) return t("answerSummaryEmpty");
            const display = text.length > 50 ? text.slice(0, 50) + "…" : text;
            return t("answerSummaryText", { v: display });
        }
    }
}

/**
 * Update the answer summary chip UI.
 */
export function updateAnswerSummary() {
    const el = $("#answerSummaryText");
    if (!el) return;

    const summary = formatCurrentAnswerSummary();
    el.textContent = summary;

    // Apply "has-value" styling when answer is selected
    const hasChoices = Array.isArray(state.choices) && state.choices.length > 0;
    let hasValue = false;
    if (hasChoices) {
        hasValue = state.selectedChoiceIndex != null;
    } else if (state.freeAnswerMode === "int") {
        hasValue = !!($("#intValue")?.value || "").trim();
    } else {
        hasValue = !!($("#freeText")?.value || "").trim();
    }

    el.classList.toggle("has-value", hasValue);
}

// ---- Clear Answer ----

/**
 * Clear the current answer and update UI.
 */
export function clearAnswer() {
    const hasChoices = Array.isArray(state.choices) && state.choices.length > 0;

    if (hasChoices) {
        state.selectedChoiceIndex = null;
        document.querySelectorAll(".choice-btn").forEach(b => {
            b.classList.remove("is-selected");
        });
    } else {
        if (state.freeAnswerMode === "int") {
            const el = $("#intValue");
            if (el) el.value = "";
        } else {
            const el = $("#freeText");
            if (el) el.value = "";
        }
    }

    updateAnswerSummary();
    applySubmitLockState();
}

// ---- Copy Utilities ----

/**
 * Copy text to clipboard with fallback.
 * @param {string} text
 * @returns {Promise<boolean>}
 */
async function copyToClipboard(text) {
    try {
        if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
            await navigator.clipboard.writeText(text);
            return true;
        }

        // Fallback for older browsers
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

export async function copyQuestion() {
    const text = state.questionPrompt || "";
    if (!text.trim()) return;
    const ok = await copyToClipboard(text);
    toast(ok ? t("copied") : t("copyFailed"), "", ok ? "info" : "error");
}

export async function copySelectedChoice() {
    if (!Array.isArray(state.choices)) return;
    const idx = state.selectedChoiceIndex;
    if (idx == null) return;

    const text = state.choices[idx] || "";
    if (!text.trim()) return;
    const ok = await copyToClipboard(text);
    toast(ok ? t("copied") : t("copyFailed"), "", ok ? "info" : "error");
}

export async function copyScratchpad() {
    initWorkspaceState();
    const text = state.ui.workspace.scratchText || "";
    if (!text.trim()) return;
    const ok = await copyToClipboard(text);
    toast(ok ? t("copied") : t("copyFailed"), "", ok ? "info" : "error");
}

// ---- Scratchpad ----

function syncScratchpadUi() {
    initWorkspaceState();
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
}

export function toggleScratchpad() {
    initWorkspaceState();
    state.ui.workspace.scratchOpen = !state.ui.workspace.scratchOpen;
    syncScratchpadUi();
}

function isCompactHeightPhone() {
    // "Smartphones with vertical resolution of 740-800px or less"
    // We also require the mobile layout breakpoint to avoid affecting desktop.
    return isMobileLayout() && window.matchMedia("(max-height: 800px)").matches;
}

// ---- Scratchpad Placement Logic ----

function applyScratchpadPlacement() {
    const toggle = $("#scratchpadToggle");
    const panel = $("#scratchpadPanel");
    const section = $("#scratchpadSection");
    const summary = $("#answerSummary");

    if (!toggle || !panel || !section) return;

    if (isMobileLayout()) {
        // MOBILE: Move toggle to Summary area
        if (toggle.parentElement !== summary) {
            summary.appendChild(toggle);
        }
        if (panel.parentElement !== section) {
            section.appendChild(panel);
        }
    } else {
        // PC:
        // 1. Move toggle back to Left Column (#answerSummary) to align with "Clear" button
        if (toggle.parentElement !== summary) {
            summary.appendChild(toggle);
        }

        // 2. Keep panel in Right Column (#scratchpadSection)
        if (panel.parentElement !== section) {
            section.appendChild(panel);
        }
    }

    // Ensure the section is visible if open
    section.classList.remove("is-relocated");

    syncScratchpadUi();
}


export function updateScratchpadText(text) {
    initWorkspaceState();
    state.ui.workspace.scratchText = text || "";
}

// ---- Calculator ----

/**
 * Safely evaluate a mathematical expression.
 * Supports: numbers, + - * / ( ) . and constants: pi, e
 * Functions: sqrt, pow, log, ln, sin, cos, tan, abs
 * @param {string} expr
 * @returns {{ value: number | null, error: string | null }}
 */
export function evalCalc(expr) {
    if (!expr || !expr.trim()) {
        return { value: null, error: null };
    }

    // Tokenize and validate
    const sanitized = expr
        .toLowerCase()
        .replace(/\s+/g, "")
        // Replace constants
        .replace(/\bpi\b/g, `(${Math.PI})`)
        .replace(/\be\b/g, `(${Math.E})`)
        // Replace functions with Math.*
        .replace(/\bsqrt\(/g, "Math.sqrt(")
        .replace(/\bpow\(/g, "Math.pow(")
        .replace(/\blog\(/g, "Math.log10(")
        .replace(/\bln\(/g, "Math.log(")
        .replace(/\bsin\(/g, "Math.sin(")
        .replace(/\bcos\(/g, "Math.cos(")
        .replace(/\btan\(/g, "Math.tan(")
        .replace(/\babs\(/g, "Math.abs(");

    // Strict validation: only allow safe characters
    // Allow: digits, ., +, -, *, /, (, ), Math, and comma for function args
    const safePattern =
        /^(?:\d+(?:\.\d+)?|[+\-*/(),]|\bMath\.(?:sqrt|pow|log|sin|cos|tan|abs)\b)+$/;
    if (!safePattern.test(sanitized)) {
        return { value: null, error: "Invalid expression" };
    }

    // Check for balanced parentheses
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
        // Use Function constructor for isolated execution
        const fn = new Function(`"use strict"; return (${sanitized});`);
        const result = fn();

        if (typeof result !== "number" || !Number.isFinite(result)) {
            return { value: null, error: "Invalid result" };
        }

        // Round to avoid floating point weirdness in display
        const rounded = Math.round(result * 1e10) / 1e10;
        return { value: rounded, error: null };
    } catch (e) {
        return { value: null, error: "Eval error" };
    }
}

export function runCalc() {
    initWorkspaceState();
    const input = $("#calcExpr");
    const resultEl = $("#calcResult");
    if (!input || !resultEl) return;

    const expr = input.value || "";
    const { value, error } = evalCalc(expr);

    if (error) {
        resultEl.textContent = error;
        resultEl.classList.add("error");
    } else if (value != null) {
        resultEl.textContent = String(value);
        resultEl.classList.remove("error");
    } else {
        resultEl.textContent = "";
        resultEl.classList.remove("error");
    }

    state.ui.workspace.calcExpr = expr;
    state.ui.workspace.calcResult = resultEl.textContent;
}

export function clearScratchpad() {
    initWorkspaceState();
    const textarea = $("#scratchpadText");
    const exprInput = $("#calcExpr");
    const resultEl = $("#calcResult");

    if (textarea) textarea.value = "";
    if (exprInput) exprInput.value = "";
    if (resultEl) {
        resultEl.textContent = "";
        resultEl.classList.remove("error");
    }

    state.ui.workspace.scratchText = "";
    state.ui.workspace.calcExpr = "";
    state.ui.workspace.calcResult = "";
}

// ---- Keyboard Shortcuts ----

/**
 * Update keyboard hint text based on current mode.
 */
export function updateKeyboardHint() {
    const el = $("#kbdHint");
    if (!el) return;

    // Hide on mobile
    if (isMobileLayout()) {
        el.textContent = "";
        return;
    }

    if (!state.inRound) {
        el.textContent = "";
        return;
    }

    const hasChoices = Array.isArray(state.choices) && state.choices.length > 0;

    if (hasChoices) {
        el.textContent = t("kbdHintMcq");
    } else if (state.freeAnswerMode === "int") {
        el.textContent = t("kbdHintInt");
    } else {
        el.textContent = t("kbdHintText");
    }
}

/**
 * Handle keyboard shortcuts for MCQ selection and submission.
 * @param {KeyboardEvent} e
 * @param {() => void} submitFn - Submit answer callback
 */
export function handleKeyboardShortcut(e, submitFn) {
    if (!state.inRound || state.submitted) return;
    if (isMobileLayout()) return;

    const target = e.target;

    // If the user is typing anywhere (inputs/textareas/selects/contenteditable),
    // NEVER trigger global shortcuts (fixes scratchpad/calc/int inputs stealing digits).
    const tag = target?.tagName;
    const isTypingContext = !!target && (
        target.isContentEditable ||
        tag === "TEXTAREA" ||
        tag === "INPUT" ||
        tag === "SELECT"
    );
    if (isTypingContext) return;

    const hasChoices = Array.isArray(state.choices) && state.choices.length > 0;
    if (!hasChoices) return;

    // MCQ mode: 1-9 for selection, Enter for submit
    const key = e.key;

    if (/^[1-9]$/.test(key)) {
        const idx = parseInt(key, 10) - 1;
        if (idx < state.choices.length) {
            state.selectedChoiceIndex = idx;
            document.querySelectorAll(".choice-btn").forEach((btn, i) => {
                btn.classList.toggle("is-selected", i === idx);
            });
            updateAnswerSummary();
            applySubmitLockState();
            e.preventDefault();
        }
    } else if (key === "Enter") {
        if (state.selectedChoiceIndex != null) {
            submitFn();
            e.preventDefault();
        }
    }
}

// ---- Initialization & Reset ----

/**
 * Initialize workspace static text.
 */
export function initWorkspaceText() {
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

/**
 * Update the enabled/disabled state of the Clear Answer button.
 * Disabled if the user has already submitted.
 */
export function updateClearButtonState() {
    const btn = $("#btnClearAnswer");
    if (btn) {
        btn.disabled = !!state.submitted;
    }
}

/**
 * Reset workspace UI state for new round.
 */
export function resetWorkspace() {
    initWorkspaceState();

    // Reset answer summary
    updateAnswerSummary();
    applySubmitLockState();
    updateClearButtonState();

    // Reset keyboard hint
    updateKeyboardHint();

    // Keep scratchpad content across rounds, reset calc
    const resultEl = $("#calcResult");
    if (resultEl) {
        resultEl.textContent = "";
        resultEl.classList.remove("error");
    }

    applyScratchpadPlacement();
}

/**
 * Bind workspace event handlers.
 */
export function bindWorkspaceEvents(submitFn) {
    initWorkspaceState();

    // Clear answer button
    $("#btnClearAnswer")?.addEventListener("click", clearAnswer);

    // Scratchpad toggle
    $("#scratchpadToggle")?.addEventListener("click", toggleScratchpad);

    // Scratchpad textarea
    $("#scratchpadText")?.addEventListener("input", (e) => {
        updateScratchpadText(e.target.value);
    });

    // Calculator
    $("#btnCalcEval")?.addEventListener("click", runCalc);
    $("#calcExpr")?.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
            e.preventDefault();
            runCalc();
        }
    });
    $("#btnScratchClear")?.addEventListener("click", clearScratchpad);

    // Keyboard shortcuts (global)
    window.addEventListener("keydown", (e) => {
        handleKeyboardShortcut(e, submitFn);
    });

    // Update on input changes for free response
    $("#freeText")?.addEventListener("input", () => {
        updateAnswerSummary();
        applySubmitLockState();
    });

    $("#intValue")?.addEventListener("input", () => {
        updateAnswerSummary();
        applySubmitLockState();
    });

    applyScratchpadPlacement();

    let raf = 0;
    window.addEventListener("resize", () => {
        if (raf) cancelAnimationFrame(raf);
        raf = requestAnimationFrame(() => {
            raf = 0;
            applyScratchpadPlacement();
        });
    }, { passive: true });
}
