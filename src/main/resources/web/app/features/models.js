import {$} from "../core/dom.js";
import {httpGetJson} from "../core/net.js";
import {escapeHtml, isMobileLayout} from "../core/utils.js";
import {t} from "../core/i18n.js";
import {state} from "../core/state.js";
import {renderModelBadgesHtml} from "../ui/badges.js";
import {showDetailModal} from "../ui/modals.js";

import {installRichTooltip} from "../ui/tooltips.js";

// ----- Rich tooltip for Question Set badges (PC only, scoped to model picker) -----
function installQsRichTooltip({wrapper, optionsList, ac}) {
    const t1 = installRichTooltip(wrapper, ac.signal);
    const t2 = installRichTooltip(optionsList, ac.signal);

    return {
        hide: () => {
            t1.hide();
            t2.hide();
        }
    };
}

// Icons

const ICON_SPEED = `<svg class="opp-stat-icon" viewBox="0 0 24 24"><path fill="currentColor" d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"></path></svg>`;
const ICON_EFFICIENCY = `<svg class="opp-stat-icon" viewBox="0 0 24 24"><path fill="currentColor" d="M12 2L2 12l10 10 10-10L12 2z"></path></svg>`;

function renderStat(icon, label, value, max = 5, className = "") {
    if (!value) return "";
    let pips = "";
    for (let i = 1; i <= max; i++) {
        pips += `<div class="pip ${i <= value ? "filled" : ""}"></div>`;
    }
    return `<div class="opp-stat ${className}" title="${label}: ${value}/${max}">${icon}<div class="opp-stat-val stat-pips">${pips}</div></div>`;
}

function resolveModelDescription(model) {
    if (!model) return "";
    // Try i18n key first
    if (model.metadata?.descriptionI18nKey) {
        const localized = t(model.metadata.descriptionI18nKey);
        // t() returns the key itself if not found, so check if it's different
        if (localized && localized !== model.metadata.descriptionI18nKey) {
            return localized;
        }
    }
    // Fallback to static description
    return model.metadata?.description || "";
}

function updateTriggerUI(triggerEl, model) {
    if (!model) return;
    const meta = model.metadata || {};

    const titleEl = triggerEl.querySelector(".trigger-title");
    const badgesEl = triggerEl.querySelector(".trigger-badges");
    const descEl = triggerEl.querySelector(".trigger-desc");
    const statsEl = triggerEl.querySelector(".trigger-stats");

    titleEl.textContent = meta.displayName || model.id;
    if (badgesEl) {
        badgesEl.innerHTML = renderModelBadgesHtml(meta);
    }

    descEl.textContent = resolveModelDescription(model);
    if (statsEl) {
        const speed = meta.speed || 0;
        const eff = meta.efficiency || 0;
        const statsHtml = `
            ${renderStat(ICON_SPEED, "Speed", speed, 5, "speed")}
            ${renderStat(ICON_EFFICIENCY, "Efficiency", eff, 5, "efficiency")}
        `;
        statsEl.innerHTML = statsHtml;
        statsEl.classList.toggle("is-empty", !statsHtml.trim());
    }
}

function renderModelSelectorWidget(container, input, models, mode) {
    // Cleanup previous instance (prevents leaking global window listeners and orphaned floating menus)
    try {
        container._selectorAbort?.abort?.();
    } catch { /* ignore */
    }
    try {
        container._selectorOptionsEl?.remove?.();
    } catch { /* ignore */
    }

    const ac = new AbortController();
    container._selectorAbort = ac;

    container.innerHTML = "";

    if (!models.length) {
        container.innerHTML = `<div class="muted small" style="padding:10px;">(no models)</div>`;
        return;
    }

    // 1. Create Structure
    const wrapper = document.createElement("div");
    wrapper.className = "model-selector";

    // 2. Create Trigger (The main button)
    const trigger = document.createElement("div");
    trigger.className = "selector-trigger";
    // Make trigger keyboard-focusable and semantically button-like
    trigger.setAttribute("role", "button");
    trigger.tabIndex = 0;
    trigger.setAttribute("aria-haspopup", "listbox");
    trigger.innerHTML = `
        <div class="trigger-content">
            <div class="trigger-title-row">
                <div class="trigger-title">Select Model</div>
                <div class="trigger-stats opt-stats-row"></div>
            </div>
            <div class="trigger-badges"></div>
            <div class="trigger-desc">...</div>
        </div>
        <svg class="trigger-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"></polyline></svg>
    `;

    // 3. Create Options Dropdown
    const optionsList = document.createElement("div");
    optionsList.className = "selector-options";
    container._selectorOptionsEl = optionsList;

    // Fill Options
    models.forEach((m) => {
        const meta = m.metadata || {};
        const speed = meta.speed || 0;
        const eff = meta.efficiency || 0;

        const opt = document.createElement("div");
        opt.className = "selector-option";
        opt.setAttribute("role", "option");
        opt.tabIndex = 0;
        if (m.id === input.value) opt.classList.add("selected");

        opt.dataset.value = m.id;

        opt.innerHTML = `
            <div class="opt-info">
                <div class="opt-name">${escapeHtml(meta.displayName)}</div>
                <div class="opt-badges">${renderModelBadgesHtml(meta)}</div>
            </div>
            <div class="opt-stats-row">
                ${renderStat(ICON_SPEED, "Speed", speed, 5, "speed")}
                ${renderStat(ICON_EFFICIENCY, "Efficiency", eff, 5, "efficiency")}
            </div>
        `;


        // Click Option Event
        opt.addEventListener("click", (e) => {
            e.stopPropagation();
            input.value = m.id;

            // Update UI state
            optionsList.querySelectorAll(".selector-option").forEach((el) => el.classList.remove("selected"));
            opt.classList.add("selected");

            updateTriggerUI(trigger, m);
            toggleMenu(false);

            // Sync global state
            state.mode = mode;
        });

        // Keyboard selection for options
        opt.addEventListener("keydown", (e) => {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                opt.click();
            }
        });

        optionsList.appendChild(opt);
    });

    wrapper.appendChild(trigger);
    wrapper.appendChild(optionsList);
    container.appendChild(wrapper);

    // Initial Trigger Update
    const currentModel = models.find((m) => m.id === input.value) || models[0];
    if (currentModel) {
        // IMPORTANT: ensure hidden input has a default id so Start works without a click
        input.value = currentModel.id;
        updateTriggerUI(trigger, currentModel);
    }

    // Rich tooltip for Question Set badges (PC only)
    const qsTooltip = installQsRichTooltip({wrapper, optionsList, ac});


    // --- Interaction Logic (The Hack) --- //

    function toggleMenu(forceState) {
        const isOpen = trigger.classList.contains("is-open");
        const newState = forceState !== undefined ? forceState : !isOpen;

        if (newState) {
            // OPENING
            trigger.classList.add("is-open");
            optionsList.classList.add("is-open");

            // HACK: Teleport to body to escape overflow:hidden/auto of the modal
            document.body.appendChild(optionsList);
            optionsList.classList.add("is-fixed");

            // Calculate Position & Size
            const rect = trigger.getBoundingClientRect();
            const viewportHeight = window.innerHeight;
            // Calculate space to the bottom of the screen with a 12px margin
            const spaceBelow = viewportHeight - rect.bottom - 12;

            optionsList.style.top = `${rect.bottom}px`;
            optionsList.style.left = `${rect.left}px`;
            optionsList.style.width = `${rect.width}px`;
            // Force it to fill the remaining space (scroll internally)
            optionsList.style.maxHeight = `${spaceBelow}px`;
        } else {
            // CLOSING
            trigger.classList.remove("is-open");
            optionsList.classList.remove("is-open");
            optionsList.classList.remove("is-fixed");
            trigger.setAttribute("aria-expanded", "false");

            // HACK: Put it back in the wrapper so it's cleaned up properly if the modal closes
            wrapper.appendChild(optionsList);

            // Clear manual styles
            optionsList.style.top = "";
            optionsList.style.left = "";
            optionsList.style.width = "";
            optionsList.style.maxHeight = "";

            // Ensure tooltip never “sticks” after close
            qsTooltip?.hide?.();
        }

    }

    trigger.addEventListener("click", (e) => {
        e.stopPropagation();
        toggleMenu();
    });

    // Mobile: tapping the SET badge shows a detail modal instead of toggling/selecting.
    // IMPORTANT: optionsList can be teleported to <body>, so use a window capture listener,
    // and scope it strictly to this widget’s wrapper/optionsList.
    const onQuestionSetBadgeTap = (e) => {
        if (!isMobileLayout()) return;

        const img = e.target?.closest?.("img.gh-badge[data-kind='questionset']");
        if (!img) return;

        // Scope: only badges inside this model selector instance (trigger or its dropdown)
        if (!(wrapper.contains(img) || optionsList.contains(img))) return;

        const name = String(img.dataset.qsName || "").trim();
        const desc = String(img.dataset.qsDesc || "").trim();
        if (!name && !desc) return;

        e.preventDefault();
        e.stopPropagation();

        // Close menu to avoid weird layering / accidental selection
        if (trigger.classList.contains("is-open")) toggleMenu(false);

        showDetailModal({
            title: name || "Question Set",
            body: desc || "",
        });
    };
    window.addEventListener("click", onQuestionSetBadgeTap, {signal: ac.signal, capture: true});

    // Trigger keyboard toggle
    trigger.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            toggleMenu();
        } else if (e.key === "Escape" && trigger.classList.contains("is-open")) {
            e.preventDefault();
            toggleMenu(false);
        }
    });

    // Close when clicking outside (bind to window because optionsList might be on body)
    const onWindowClick = (e) => {
        if (trigger.contains(e.target)) return;
        if (optionsList.contains(e.target)) return;

        if (trigger.classList.contains("is-open")) {
            toggleMenu(false);
        }
    };
    window.addEventListener("click", onWindowClick, {signal: ac.signal});

    // Close on window resize to prevent alignment issues
    const onResize = () => {
        if (trigger.classList.contains("is-open")) {
            toggleMenu(false);
        }
    };
    window.addEventListener("resize", onResize, {signal: ac.signal, passive: true});

    // Close on scroll (prevents misalignment if the page/viewport moves)
    const onScroll = () => {
        if (trigger.classList.contains("is-open")) toggleMenu(false);
    };
    window.addEventListener("scroll", onScroll, {signal: ac.signal, passive: true});

    // Close on Escape even if focus isn't on trigger
    const onKeydown = (e) => {
        if (e.key === "Escape" && trigger.classList.contains("is-open")) {
            e.preventDefault();
            toggleMenu(false);
        }
    };
    window.addEventListener("keydown", onKeydown, {signal: ac.signal});
}

export function populateOpponentSelects() {
    ["LIGHTWEIGHT", "PREMIUM"].forEach((mode) => {
        const models = state.models[mode] || [];
        const container = $(mode === "LIGHTWEIGHT" ? "#opponentLight" : "#opponentPremium");
        const input = $(mode === "LIGHTWEIGHT" ? "#opponentLightVal" : "#opponentPremiumVal");

        if (!container) return;

        renderModelSelectorWidget(container, input, models, mode);
    });
}

export async function loadModels() {
    const [light, prem] = await Promise.all([
        httpGetJson("/api/v1/models?mode=LIGHTWEIGHT"),
        httpGetJson("/api/v1/models?mode=PREMIUM"),
    ]);
    state.models.LIGHTWEIGHT = light.models || [];
    state.models.PREMIUM = prem.models || [];
    populateOpponentSelects();
}
