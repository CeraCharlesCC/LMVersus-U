import { $ } from "../core/dom.js";
import { t } from "../core/i18n.js";
import { fmtMs, fmtPoints, safeLsGet, safeLsSet } from "../core/utils.js";
import { state, STORAGE_KEY_LANDING } from "../core/state.js";

function matchEndSubtitle(reason) {
    const r = String(reason || "");
    if (r === "completed") return t("matchEndSubCompleted");
    if (r === "timeout") return t("matchEndSubTimeout");
    if (r === "max_lifespan") return t("matchEndSubMax");
    return t("matchEndSubCancelled");
}

function matchEndTitleAndBadge(winner, reason) {
    const w = String(winner || "");
    const r = String(reason || "");

    if (r !== "completed") {
        return { title: t("matchEndNone"), badge: "🏁", klass: "none" };
    }
    if (w === "HUMAN") return { title: t("matchEndWin"), badge: "🏆", klass: "win" };
    if (w === "LLM") return { title: t("matchEndLose"), badge: "😵", klass: "lose" };
    if (w === "TIE") return { title: t("matchEndTie"), badge: "🤝", klass: "tie" };
    return { title: t("matchEndNone"), badge: "🏁", klass: "none" };
}

export function isMatchEndVisible() {
    return !!state.ui.matchEndVisible;
}

export function showMatchEndModal(payload) {
    const overlay = $("#matchEndOverlay");
    const modal = overlay.querySelector(".end-modal");

    const { title, badge, klass } = matchEndTitleAndBadge(payload.winner, payload.reason);

    modal.classList.remove("win", "lose", "tie", "none");
    modal.classList.add(klass);

    $("#endBadge").textContent = badge;
    $("#endTitle").textContent = title;
    $("#endSub").textContent = matchEndSubtitle(payload.reason);

    $("#endHumanName").textContent = state.nickname || t("yourId");
    $("#endLlmName").textContent = state.opponentDisplayName || "LLM";

    $("#endHumanScore").textContent = fmtPoints(payload.humanTotalScore);
    $("#endLlmScore").textContent = fmtPoints(payload.llmTotalScore);

    $("#endRounds").textContent = `${payload.roundsPlayed}/${payload.totalRounds}`;
    $("#endDuration").textContent = fmtMs(payload.durationMs);

    overlay.classList.remove("hidden");
    overlay.setAttribute("aria-hidden", "false");
    state.ui.matchEndVisible = true;

    requestAnimationFrame(() => $("#btnEndLobby")?.focus());
}

export function hideMatchEndModal() {
    const overlay = $("#matchEndOverlay");
    if (!overlay) return;
    overlay.classList.add("hidden");
    overlay.setAttribute("aria-hidden", "true");
    state.ui.matchEndVisible = false;
}

/** ---- LICENSE modal ---- */
let lastFocusEl = null;

export function showLicenseModal() {
    const overlay = $("#licenseOverlay");
    if (!overlay) return;

    lastFocusEl = document.activeElement;

    overlay.classList.remove("hidden");
    overlay.setAttribute("aria-hidden", "false");

    requestAnimationFrame(() => {
        $("#btnCloseLicense")?.focus();
    });
}

export function hideLicenseModal() {
    const overlay = $("#licenseOverlay");
    if (!overlay) return;

    overlay.classList.add("hidden");
    overlay.setAttribute("aria-hidden", "true");

    if (lastFocusEl && typeof lastFocusEl.focus === "function") {
        lastFocusEl.focus();
    }
    lastFocusEl = null;
}

export async function loadLicenseHtml() {
    const host = document.getElementById("licenseContent");
    if (!host) return;

    try {
        const [resDs, resFe] = await Promise.all([
            fetch("./license-dataset.html", { cache: "no-cache" }),
            fetch("./license-frontend.html", { cache: "no-cache" }),
        ]);

        const parts = [];
        if (resDs.ok) parts.push(await resDs.text());
        if (resFe.ok) parts.push(await resFe.text());

        if (parts.length === 0) {
            throw new Error("Failed to load any license files.");
        }

        host.innerHTML = parts.join("<hr />");
    } catch (e) {
        host.innerHTML = `<p class="muted small">Failed to load license.</p>`;
        console.error(e);
    }
}

export function checkLandingPopup() {
    // Preserve existing behavior exactly
    safeLsSet(STORAGE_KEY_LANDING, "true");
    const ack = safeLsGet(STORAGE_KEY_LANDING);
    if (!ack) {
        const overlay = $("#landingOverlay");
        if (overlay) {
            overlay.classList.remove("hidden");
            overlay.setAttribute("aria-hidden", "false");
            setTimeout(() => $("#btnLandingDismiss")?.focus(), 100);
        }
    }
}
