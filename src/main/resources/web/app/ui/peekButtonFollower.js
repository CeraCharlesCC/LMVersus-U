/**
 * Peek Button Scroll Follower
 *
 * Makes the "Show Reasoning" button follow the user's visible viewport
 * within the blur area. The button stays centered vertically in the
 * visible portion of the reasoning panel.
 *
 * Optimized for high-frequency updates (60fps) using requestAnimationFrame
 * throttling and cached element references.
 */

import { $ } from "../core/dom.js";

/** Cached element references (reused across updates) */
let reasoningWrap = null;
let peekBtn = null;
let panelBody = null;

/** RAF handle for throttling */
let rafHandle = 0;

/** Flag to track if scroll listener is attached */
let listenerAttached = false;

/**
 * Initialize element references.
 * Call this once after DOM is ready.
 */
export function initPeekButtonFollower(signal) {
    reasoningWrap = $("#reasoningWrap");
    peekBtn = $("#btnPeek");
    panelBody = $("#llmPanel .panel-body");

    if (!panelBody || !reasoningWrap || !peekBtn) return;

    if (!listenerAttached) {
        panelBody.addEventListener("scroll", scheduleUpdate, { passive: true, signal });
        window.addEventListener("resize", scheduleUpdate, { passive: true, signal });
        listenerAttached = true;
    }

    // Initial position
    updatePeekButtonPosition();
}

/**
 * Schedule a position update using requestAnimationFrame for throttling.
 * This ensures we don't calculate positions more than 60 times per second.
 */
function scheduleUpdate() {
    if (rafHandle) return; // Already scheduled
    rafHandle = requestAnimationFrame(() => {
        rafHandle = 0;
        updatePeekButtonPosition();
    });
}

/**
 * Update the peek button position to follow scroll.
 * The button should stay centered in the visible portion of the reasoning wrap
 * that overlaps with the blur area.
 */
function updatePeekButtonPosition() {
    if (!reasoningWrap || !peekBtn || !panelBody) return;

    // Don't update if not masked
    if (!reasoningWrap.classList.contains("masked")) return;

    const wrapRect = reasoningWrap.getBoundingClientRect();
    const panelRect = panelBody.getBoundingClientRect();

    // The blur mask starts at 34% from top (100% - 66% height = 34%)
    const blurStartRatio = 0.34;
    const buttonHeight = 50; // Approximate button height for padding

    // Calculate the wrap height and blur zone boundaries
    const wrapHeight = wrapRect.height;
    const blurStartPx = wrapHeight * blurStartRatio;

    // Calculate the visible portion of the wrap within the panel viewport
    // visibleTop: distance from wrap top to where the visible area starts
    // visibleBottom: distance from wrap top to where the visible area ends
    const visibleTop = Math.max(0, panelRect.top - wrapRect.top);
    const visibleBottom = Math.min(wrapHeight, panelRect.bottom - wrapRect.top);

    // If the wrap is not visible at all, skip
    if (visibleBottom <= visibleTop) return;

    // Calculate the visible portion of the blur area specifically
    // Blur area starts at blurStartPx and ends at wrapHeight
    const blurVisibleTop = Math.max(visibleTop, blurStartPx);
    const blurVisibleBottom = Math.min(visibleBottom, wrapHeight - buttonHeight / 2);

    // If no blur area is visible, keep button at default position
    if (blurVisibleBottom <= blurVisibleTop) return;

    // Center the button in the visible blur area
    const centerY = (blurVisibleTop + blurVisibleBottom) / 2;
    const topPercent = (centerY / wrapHeight) * 100;

    // Clamp between 40% and 92% (stay within blur, with some padding)
    const clamped = Math.max(40, Math.min(92, topPercent));
    peekBtn.style.setProperty("--peek-top", `${clamped}%`);
}

/**
 * Force an immediate position update.
 * Call this after reasoning updates if needed.
 */
export function updatePeekButtonNow() {
    updatePeekButtonPosition();
}

/**
 * Reset the button position to default.
 * Call this when the reasoning is cleared.
 */
export function resetPeekButtonPosition() {
    if (peekBtn) {
        peekBtn.style.removeProperty("--peek-top");
    }
}
