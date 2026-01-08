import { $ } from "../core/dom.js";
import { escapeHtml } from "../core/utils.js";
import { t } from "../core/i18n.js";
import { RateLimitError } from "../core/net.js";

export function toast(title, body, kind = "info", ttlMs = 3200) {
    const host = $("#toastHost");
    const el = document.createElement("div");
    el.className = `toast ${kind}`;
    el.innerHTML = `
    <button class="t-close" type="button" aria-label="Close">✕</button>
    <div class="t-title">${escapeHtml(title)}</div>
    <div class="t-body">${escapeHtml(body)}</div>
  `;

    const close = () => {
        if (el.classList.contains("closing")) return;
        el.classList.add("closing");
        el.addEventListener("animationend", () => el.remove(), { once: true });
    };

    el.querySelector(".t-close").addEventListener("click", close);
    host.appendChild(el);

    if (ttlMs > 0) {
        setTimeout(() => {
            if (el.isConnected) close();
        }, ttlMs);
    }
}

export function showNetError(e) {
    if (e && e.name === "RateLimitError") {
        toast(t("rateLimitedTitle"), e.message, "warn", 5200);
        return;
    }
    // (also handle instances just in case)
    if (e instanceof RateLimitError) {
        toast(t("rateLimitedTitle"), e.message, "warn", 5200);
        return;
    }
    toast(t("toastError"), e?.message || String(e), "error");
}
