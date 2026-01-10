import { $ } from "../core/dom.js";

const CLOSE_TIMEOUT_MS = 1600;
const OPEN_TIMEOUT_MS = 1800;

const DEFAULT_HOLD_MS = 6400;

let transitionActive = false;
let pendingTransition = false;

let runToken = 0;

export function setVsTransitionPending(pending) {
    pendingTransition = !!pending;
}

export function isVsTransitionPending() {
    return pendingTransition;
}

export function isVsTransitionActive() {
    return transitionActive;
}

function sleep(ms) {
    return new Promise((r) => setTimeout(r, ms));
}

function waitForTransformTransition(el, timeoutMs) {
    return new Promise((resolve) => {
        if (!el) return resolve();

        let done = false;
        const finish = () => {
            if (done) return;
            done = true;
            cleanup();
            resolve();
        };

        const onEnd = (e) => {
            if (e.target !== el) return;
            if (e.propertyName !== "transform") return;
            finish();
        };

        const cleanup = () => {
            clearTimeout(t);
            el.removeEventListener("transitionend", onEnd);
        };

        el.addEventListener("transitionend", onEnd);
        const t = setTimeout(finish, timeoutMs);
    });
}

function hardResetOverlay(overlay) {
    if (!overlay) return;
    overlay.classList.add("hidden");
    overlay.classList.remove("is-entering", "is-closed", "is-opening");
    overlay.setAttribute("aria-hidden", "true");
}

/**
 * Play the VS transition animation.
 * @param {Object} opts
 * @param {string} opts.humanName
 * @param {string} opts.llmName
 * @param {() => void | Promise<void>} [opts.onSwitch] Called AFTER shutters fully close.
 * @param {number} [opts.holdMs] How long to hold the VS screen before opening.
 */
export async function playVsTransition({
                                           humanName,
                                           llmName,
                                           onSwitch,
                                           holdMs = DEFAULT_HOLD_MS,
                                       } = {}) {
    pendingTransition = false;

    const overlay = $("#vsOverlay");
    if (!overlay) return;

    // Cancel any existing run and start a new token
    cancelVsTransition();
    const myToken = ++runToken;

    transitionActive = true;

    // Set names
    const humanEl = $("#vsHumanName");
    const llmEl = $("#vsLlmName");
    if (humanEl) humanEl.textContent = humanName || "You";
    if (llmEl) llmEl.textContent = llmName || "LLM";

    const left = overlay.querySelector(".vs-shutter-left");
    const right = overlay.querySelector(".vs-shutter-right");

    // Show overlay in a known baseline state (shutters are "open"/offscreen by default CSS)
    overlay.classList.remove("hidden");
    overlay.setAttribute("aria-hidden", "false");
    overlay.classList.remove("is-entering", "is-closed", "is-opening");

    // Force style flush so the next class change transitions reliably
    // eslint-disable-next-line no-unused-expressions
    overlay.offsetHeight;

    // 1) CLOSE shutters
    overlay.classList.add("is-entering");

    await Promise.all([
        waitForTransformTransition(left, CLOSE_TIMEOUT_MS),
        waitForTransformTransition(right, CLOSE_TIMEOUT_MS),
    ]);

    if (myToken !== runToken) return; // canceled mid-run

    overlay.classList.remove("is-entering");
    overlay.classList.add("is-closed");

    // 2) Switch screens behind the closed shutters
    try {
        await onSwitch?.();
    } catch (e) {
        console.error(e);
    }

    if (myToken !== runToken) return; // canceled mid-run

    // 3) Hold the VS screen a bit (so it feels intentional)
    await sleep(Math.max(0, holdMs | 0));

    if (myToken !== runToken) return; // canceled mid-run

    // 4) OPEN shutters and hide
    overlay.classList.remove("is-closed");
    overlay.classList.add("is-opening");

    await Promise.all([
        waitForTransformTransition(left, OPEN_TIMEOUT_MS),
        waitForTransformTransition(right, OPEN_TIMEOUT_MS),
    ]);

    if (myToken !== runToken) return; // canceled mid-run

    overlay.classList.remove("is-opening");
    overlay.classList.add("hidden");
    overlay.setAttribute("aria-hidden", "true");

    transitionActive = false;
}

export function cancelVsTransition() {
    pendingTransition = false;
    transitionActive = false;

    // invalidate any in-flight playVsTransition()
    runToken++;

    const overlay = $("#vsOverlay");
    if (overlay) hardResetOverlay(overlay);
}
