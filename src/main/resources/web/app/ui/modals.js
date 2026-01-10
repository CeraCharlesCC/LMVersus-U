import { $ } from "../core/dom.js";
import { t } from "../core/i18n.js";
import { fmtMs, fmtPoints } from "../core/utils.js";

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
        return {title: t("matchEndNone"), badge: "🏁", klass: "none"};
    }
    if (w === "HUMAN") return {title: t("matchEndWin"), badge: "🏆", klass: "win"};
    if (w === "LLM") return {title: t("matchEndLose"), badge: "😵", klass: "lose"};
    if (w === "TIE") return {title: t("matchEndTie"), badge: "🤝", klass: "tie"};
    return {title: t("matchEndNone"), badge: "🏁", klass: "none"};
}

export function renderMatchEndModal(state) {
    const overlay = $("#matchEndOverlay");
    if (!overlay) return;
    const payload = state.ui.matchEnd;
    if (!payload) {
        overlay.classList.add("hidden");
        overlay.setAttribute("aria-hidden", "true");
        return;
    }
    const modal = overlay.querySelector(".end-modal");

    const {title, badge, klass} = matchEndTitleAndBadge(payload.winner, payload.reason);

    modal.classList.remove("win", "lose", "tie", "none");
    modal.classList.add(klass);

    $("#endBadge").textContent = badge;
    $("#endTitle").textContent = title;
    $("#endSub").textContent = matchEndSubtitle(payload.reason);

    $("#endHumanName").textContent = state.player.nickname || t("yourId");
    $("#endLlmName").textContent = state.session.opponentDisplayName || "LLM";

    $("#endHumanScore").textContent = fmtPoints(payload.humanTotalScore);
    $("#endLlmScore").textContent = fmtPoints(payload.llmTotalScore);

    $("#endRounds").textContent = `${payload.roundsPlayed}/${payload.totalRounds}`;
    $("#endDuration").textContent = fmtMs(payload.durationMs);

    overlay.classList.remove("hidden");
    overlay.setAttribute("aria-hidden", "false");

    requestAnimationFrame(() => $("#btnEndLobby")?.focus());
}

export function renderLandingOverlay(state) {
    const overlay = $("#landingOverlay");
    if (!overlay) return;
    const shouldShow = !state.ui.landingAcked;
    overlay.classList.toggle("hidden", !shouldShow);
    overlay.setAttribute("aria-hidden", shouldShow ? "false" : "true");
    if (shouldShow) {
        setTimeout(() => $("#btnLandingDismiss")?.focus(), 100);
    }
}

/** ---- DETAIL modal (for mobile question set badge) ---- */
let detailLastFocusEl = null;

function ensureDetailOverlay() {
    let overlay = $("#detailOverlay");
    if (overlay) return overlay;

    overlay = document.createElement("div");
    overlay.id = "detailOverlay";
    overlay.className = "overlay hidden";
    overlay.setAttribute("aria-hidden", "true");

    overlay.innerHTML = `
      <section class="detail-modal" role="dialog" aria-modal="true" aria-labelledby="detailTitle">
        <div class="detail-head">
          <div class="detail-title" id="detailTitle"></div>
          <button id="btnDetailClose" class="btn ghost small" type="button" aria-label="Close">✕</button>
        </div>
        <div class="detail-body" id="detailBody"></div>
        <div class="detail-actions">
          <button id="btnDetailOk" class="btn primary" type="button">OK</button>
        </div>
      </section>
    `;

    // Prefer attaching under #app so stacking matches other overlays
    const host = document.getElementById("app") || document.body;
    host.appendChild(overlay);

    // Close on background click
    overlay.addEventListener("click", (e) => {
        if (e.target && e.target.id === "detailOverlay") hideDetailModal();
    });

    overlay.querySelector("#btnDetailClose")?.addEventListener("click", hideDetailModal);
    overlay.querySelector("#btnDetailOk")?.addEventListener("click", hideDetailModal);

    // Escape to close (scoped to this modal being open)
    window.addEventListener("keydown", (e) => {
        if (e.key !== "Escape") return;
        if (overlay.classList.contains("hidden")) return;
        hideDetailModal();
    });

    return overlay;
}

export function showDetailModal({title = "", body = ""} = {}) {
    const overlay = ensureDetailOverlay();
    if (!overlay) return;

    detailLastFocusEl = document.activeElement;

    const titleEl = overlay.querySelector("#detailTitle");
    const bodyEl = overlay.querySelector("#detailBody");

    if (titleEl) titleEl.textContent = String(title || "");
    if (bodyEl) bodyEl.textContent = String(body || "");

    overlay.classList.remove("hidden");
    overlay.setAttribute("aria-hidden", "false");

    requestAnimationFrame(() => {
        overlay.querySelector("#btnDetailOk")?.focus?.();
    });
}

export function hideDetailModal() {
    const overlay = $("#detailOverlay");
    if (!overlay) return;

    overlay.classList.add("hidden");
    overlay.setAttribute("aria-hidden", "true");

    if (detailLastFocusEl && typeof detailLastFocusEl.focus === "function") {
        detailLastFocusEl.focus();
    }
    detailLastFocusEl = null;
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
            fetch("./license-dataset.html", {cache: "no-cache"}),
            fetch("./license-frontend.html", {cache: "no-cache"}),
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


