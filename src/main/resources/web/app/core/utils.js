export function fmtMs(ms) {
    ms = Math.max(0, ms | 0);
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const r = s % 60;
    const mm = String(m).padStart(2, "0");
    const rr = String(r).padStart(2, "0");
    return `${mm}:${rr}`;
}

export function escapeHtml(s) {
    return String(s ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

export function isMobileLayout() {
    return !!(window.matchMedia && window.matchMedia("(max-width: 760px)").matches);
}

export function safeLsGet(key) {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
}

export function safeLsSet(key, val) {
    try {
        localStorage.setItem(key, val);
        return true;
    } catch {
        return false;
    }
}

export function newCommandId() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return window.crypto.randomUUID();
    }

    const bytes = new Uint8Array(16);
    if (window.crypto && typeof window.crypto.getRandomValues === "function") {
        window.crypto.getRandomValues(bytes);
    } else {
        for (let i = 0; i < bytes.length; i += 1) {
            bytes[i] = Math.floor(Math.random() * 256);
        }
    }

    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;

    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function fmtPoints(x) {
    const n = Number(x);
    if (!Number.isFinite(n)) return "0";
    return n
        .toFixed(2)
        .replace(/\.00$/, "")
        .replace(/(\.\d)0$/, "$1");
}

export function fmtScore(x) {
    if (x == null) return "-";
    const n = Number(x);
    if (!Number.isFinite(n)) return "-";
    return fmtPoints(n);
}

export function normalizeChoiceForHeuristic(s) {
    s = String(s ?? "").trim();
    if (!s) return "";

    s = s.replace(/\[([^\]]+)\]\([^)]+\)/g, "$1");
    s = s.replace(/`[^`]*`/g, "x");

    s = s.replace(/\$\$[\s\S]*?\$\$/g, "M");
    s = s.replace(/\$[^$]+\$/g, "m");
    s = s.replace(/\\\[[\s\S]*?\\\]/g, "M");
    s = s.replace(/\\\([\s\S]*?\\\)/g, "m");

    s = s.replace(/[*_>#]/g, "");
    s = s.replace(/\s+/g, " ").trim();
    return s;
}

export function isChoiceCompact(src) {
    const raw = String(src ?? "");

    if (raw.includes("\n")) return false;
    if (raw.includes("```")) return false;
    if (raw.includes("|")) return false;
    if (raw.includes("$$") || raw.includes("\\[")) return false;

    const norm = normalizeChoiceForHeuristic(raw);
    return norm.length > 0 && norm.length <= 42;
}
