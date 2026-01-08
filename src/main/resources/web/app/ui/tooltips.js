import { isMobileLayout } from "../core/utils.js";

let _tooltipEl = null;

function ensureTooltipEl() {
    if (_tooltipEl && _tooltipEl.isConnected) return _tooltipEl;
    const el = document.createElement("div");
    el.className = "qs-tooltip";
    // We reuse the class and structure defined in styles.css for "Question Set Rich Tooltip"
    el.innerHTML = `<div class="qs-tt-title"></div><div class="qs-tt-body"></div>`;
    document.body.appendChild(el);
    _tooltipEl = el;
    return el;
}

function stripNativeTitle(img) {
    // Prevent the native tooltip from appearing
    if (img && img.getAttribute && img.getAttribute("title")) {
        img.dataset._nativeTitle = img.getAttribute("title") || "";
        img.removeAttribute("title");
    }
}

function positionTooltip(img, tooltipEl) {
    const pad = 10;
    const r = img.getBoundingClientRect();
    const tr = tooltipEl.getBoundingClientRect();

    // Prefer to the right; fallback left; clamp to viewport
    let x = r.right + 10;
    if (x + tr.width > window.innerWidth - pad) x = r.left - tr.width - 10;
    x = Math.max(pad, Math.min(x, window.innerWidth - tr.width - pad));

    // Prefer vertically aligned near the badge
    let y = r.top - 6;
    y = Math.max(pad, Math.min(y, window.innerHeight - tr.height - pad));

    tooltipEl.style.left = `${x}px`;
    tooltipEl.style.top = `${y}px`;
}

/**
 * Installs generic rich tooltip behavior on a container.
 * Supports:
 *  - [data-kind="questionset"] (uses data-qs-name, data-qs-desc)
 *  - [data-kind="difficulty"] (uses fixed title "Difficulty", data-diff-name)
 */
export function installRichTooltip(container, signal) {
    const tooltipEl = ensureTooltipEl();
    let activeImg = null;

    const hide = () => {
        activeImg = null;
        tooltipEl.classList.remove("is-visible");
    };

    const showFor = (img) => {
        if (!img) return;
        if (isMobileLayout()) return; // PC only

        const kind = img.dataset.kind;
        let name = "";
        let desc = "";

        if (kind === "questionset") {
            name = String(img.dataset.qsName || "").trim();
            desc = String(img.dataset.qsDesc || "").trim();
            if (!name && !desc) name = "Question Set";
        } else if (kind === "difficulty") {
            const val = String(img.dataset.diffName || "").trim();
            if (!val) return;
            name = "Difficulty";
            desc = val;
        } else {
            return;
        }

        stripNativeTitle(img);

        const titleEl = tooltipEl.querySelector(".qs-tt-title");
        const bodyEl = tooltipEl.querySelector(".qs-tt-body");
        if (titleEl) titleEl.textContent = name;
        if (bodyEl) bodyEl.textContent = desc;

        // Make visible first so we can measure it, then position.
        tooltipEl.classList.add("is-visible");
        positionTooltip(img, tooltipEl);
        activeImg = img;
    };

    const onOver = (e) => {
        if (isMobileLayout()) return;
        const img = e.target?.closest?.("img.gh-badge[data-kind]");
        if (!img || !container.contains(img)) return;
        showFor(img);
    };

    const onOut = (e) => {
        if (!activeImg) return;
        const to = e.relatedTarget;
        const nextImg = to?.closest?.("img.gh-badge[data-kind]");
        if (nextImg && container.contains(nextImg)) {
            showFor(nextImg);
            return;
        }
        hide();
    };

    const onResize = () => {
        if (!activeImg) return;
        if (isMobileLayout()) return hide();
        positionTooltip(activeImg, tooltipEl);
    };

    const opts = { signal };
    container.addEventListener("mouseover", onOver, opts);
    container.addEventListener("mouseout", onOut, opts);
    container.addEventListener("focusin", onOver, opts);
    container.addEventListener("focusout", hide, opts);

    window.addEventListener("resize", onResize, { signal, passive: true });
    window.addEventListener("scroll", hide, { signal, passive: true });

    return { hide };
}
