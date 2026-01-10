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
export function initPeekButtonFollower() {
    reasoningWrap = $("#reasoningWrap");
    peekBtn = $("#btnPeek");
    panelBody = $("#llmPanel .panel-body");

    if (!panelBody || !reasoningWrap || !peekBtn) return;

    if (!listenerAttached) {
        panelBody.addEventListener("scroll", scheduleUpdate, { passive: true });
        window.addEventListener("resize", scheduleUpdate, { passive: true });
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
    const blurEndPadding = 60; // Bottom padding for the button

    // Calculate the wrap height and blur zone boundaries
    const wrapHeight = wrapRect.height;
    const blurStartPx = wrapHeight * blurStartRatio;

    // Calculate visible area of the wrap within the scroll container
    const visibleTop = Math.max(panelRect.top, wrapRect.top) - wrapRect.top;
    const visibleBottom = Math.min(panelRect.bottom, wrapRect.bottom) - wrapRect.top;

    // Clamp to blur area only (blur starts at 34% from top)
    const blurVisibleTop = Math.max(visibleTop, blurStartPx);
    const blurVisibleBottom = Math.min(visibleBottom, wrapHeight - blurEndPadding);

    // Center the button in the visible blur area
    if (blurVisibleBottom > blurVisibleTop) {
        const centerY = (blurVisibleTop + blurVisibleBottom) / 2;
        const topPercent = (centerY / wrapHeight) * 100;

        // Clamp between 40% and 88% (stay within blur, with some padding)
        const clamped = Math.max(40, Math.min(88, topPercent));
        peekBtn.style.setProperty("--peek-top", `${clamped}%`);
    }
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
