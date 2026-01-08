import {t} from "./i18n.js";

export function wsUrl() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${location.host}/ws/game`;
}

export class RateLimitError extends Error {
    constructor(retryAfterSeconds) {
        const secs =
            Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0
                ? Math.ceil(retryAfterSeconds)
                : null;

        const msg = secs
            ? t("rateLimitedMsg", {s: secs})
            : t("rateLimitedMsgNoTime");

        super(msg);
        this.name = "RateLimitError";
        this.retryAfterSeconds = secs;
    }
}

export async function readErrorBody(res) {
    const ct = (res.headers.get("content-type") || "").toLowerCase();
    try {
        if (ct.includes("application/json")) {
            const j = await res.json();
            const msgParts = [];
            if (j?.message) msgParts.push(String(j.message));
            else if (j?.error) msgParts.push(String(j.error));
            if (j?.details && Array.isArray(j.details) && j.details.length) {
                msgParts.push(j.details.join("; "));
            }
            if (j?.errorId) msgParts.push(`errorId=${String(j.errorId).slice(0, 8)}`);
            const msg = msgParts.filter(Boolean).join(" • ");
            return msg || `${res.status} ${res.statusText}`;
        }
        const text = await res.text();
        return text || `${res.status} ${res.statusText}`;
    } catch {
        return `${res.status} ${res.statusText}`;
    }
}

export function retryAfterSecondsFromHeaders(res) {
    const ra = Number(res.headers.get("Retry-After"));
    if (Number.isFinite(ra) && ra > 0) return ra;

    const resetMs = Number(res.headers.get("X-RateLimit-Reset"));
    if (Number.isFinite(resetMs) && resetMs > 0) return Math.ceil(resetMs / 1000);

    return null;
}

export async function httpGetJson(path) {
    const res = await fetch(path, {credentials: "include"});
    if (!res.ok) {
        if (res.status === 429) {
            throw new RateLimitError(retryAfterSecondsFromHeaders(res));
        }
        throw new Error(await readErrorBody(res));
    }
    return await res.json();
}
