/**
 * Fighting-game-style VS Transition Overlay
 * 
 * Displays a dramatic "VS" screen when a new match starts,
 * featuring player names sliding in from left/right with
 * a pulsing "V.S." in the center.
 */

import { $ } from "../core/dom.js";

const TRANSITION_DURATION_MS = 2000;
let transitionActive = false;
let pendingTransition = false;

/**
 * Mark that a VS transition should play on the next session_joined event.
 * This is set when the user initiates a new match from the lobby.
 */
export function setVsTransitionPending(pending) {
    pendingTransition = pending;
}

/**
 * Check if a VS transition is pending.
 */
export function isVsTransitionPending() {
    return pendingTransition;
}

/**
 * Check if a VS transition is currently active.
 */
export function isVsTransitionActive() {
    return transitionActive;
}

/**
 * Play the VS transition animation.
 * @param {Object} opts
 * @param {string} opts.humanName - The human player's name
 * @param {string} opts.llmName - The LLM opponent's name
 * @returns {Promise<void>} Resolves when the transition completes
 */
export function playVsTransition({ humanName, llmName }) {
    return new Promise((resolve) => {
        pendingTransition = false;

        /*
        const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        if (reducedMotion) {
            resolve();
            return;
        }
         */

        const overlay = $("#vsOverlay");
        if (!overlay) {
            resolve();
            return;
        }

        transitionActive = true;

        // Set player names
        const humanEl = $("#vsHumanName");
        const llmEl = $("#vsLlmName");
        if (humanEl) humanEl.textContent = humanName || "You";
        if (llmEl) llmEl.textContent = llmName || "LLM";

        // Show the overlay and trigger animations
        overlay.classList.remove("hidden");
        overlay.classList.add("is-active");

        // Wait for animation to complete
        setTimeout(() => {
            overlay.classList.remove("is-active");
            overlay.classList.add("is-exiting");

            // Wait for exit animation
            setTimeout(() => {
                overlay.classList.remove("is-exiting");
                overlay.classList.add("hidden");
                transitionActive = false;
                resolve();
            }, 400);
        }, TRANSITION_DURATION_MS);
    });
}

/**
 * Immediately cancel any active transition (e.g., on error).
 */
export function cancelVsTransition() {
    pendingTransition = false;
    transitionActive = false;
    const overlay = $("#vsOverlay");
    if (overlay) {
        overlay.classList.remove("is-active", "is-exiting");
        overlay.classList.add("hidden");
    }
}
