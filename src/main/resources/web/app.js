/* app.js - Vanilla JS frontend for LMVU (Ktor same-origin) */

const $ = (sel, root = document) => root.querySelector(sel);

const md = window.markdownit({
    html: false,
    linkify: true,
    breaks: true,
});

const MAX_NICKNAME_LEN = 16;
const STORAGE_KEY_NICKNAME = "lmvu_nickname";
const STORAGE_KEY_LANDING = "lmvu_landing_acked";

function detectLang() {
    const raw = (navigator.language || "en").toLowerCase();
    if (raw.startsWith("ja")) return "ja";
    return "en";
}

const LANG = detectLang();

// Icons
const ICON_SPEED = `<svg class="opp-stat-icon" viewBox="0 0 24 24"><path fill="currentColor" d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"></path></svg>`;
const ICON_EFFICIENCY = `<svg class="opp-stat-icon" viewBox="0 0 24 24"><path fill="currentColor" d="M12 2L2 12l10 10 10-10L12 2z"></path></svg>`;

let I18N_EN = null;
let I18N_CUR = null;

function renderStat(icon, label, value, max = 5, className = "") {
    if (!value) return "";
    let pips = "";
    for (let i = 1; i <= max; i++) {
        pips += `<div class="pip ${i <= value ? 'filled' : ''}"></div>`;
    }
    return `<div class="opp-stat ${className}" title="${label}: ${value}/${max}">${icon}<div class="opp-stat-val stat-pips">${pips}</div></div>`;
}

function renderOpponentCard(m, mode) {
    const card = document.createElement("div");
    card.className = "opponent-card";
    card.dataset.id = m.id;
    card.role = "option";
    card.ariaSelected = "false";
    card.tabIndex = 0;

    const meta = m.metadata || {};
    const speed = meta.speed || 0;
    const eff = meta.efficiency || 0;

    card.innerHTML = `
      <div class="opp-card-head">
        <div class="opp-name">${escapeHtml(meta.displayName || m.id)}</div>
      </div>
      <div class="opp-stats">
         ${renderStat(ICON_SPEED, "Speed", speed, 5, "speed")}
         ${renderStat(ICON_EFFICIENCY, "Efficiency", eff, 5, "efficiency")}
      </div>
    `;

    card.addEventListener("click", () => selectOpponent(mode, m.id));
    card.addEventListener("keydown", (e) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            selectOpponent(mode, m.id);
        }
    });

    return card;
}

function selectOpponent(mode, id) {
    const listId = mode === "LIGHTWEIGHT" ? "opponentLight" : "opponentPremium";
    const inputId = mode === "LIGHTWEIGHT" ? "opponentLightVal" : "opponentPremiumVal";

    document.querySelectorAll(`#${listId} .opponent-card`).forEach(c => {
        const selected = c.dataset.id === id;
        c.classList.toggle("is-selected", selected);
        c.ariaSelected = String(selected);
    });

    const input = document.getElementById(inputId);
    if (input) input.value = id;

    state.mode = mode;
    updateOpponentHint();
}

function formatTemplate(str, vars) {
    if (!vars) return String(str ?? "");
    return String(str ?? "").replace(/\{(\w+)\}/g, (_, k) =>
        Object.prototype.hasOwnProperty.call(vars, k) ? String(vars[k]) : `{${k}}`
    );
}

function t(key, vars) {
    const raw =
        (I18N_CUR && I18N_CUR[key]) ??
        (I18N_EN && I18N_EN[key]) ??
        key;
    return formatTemplate(raw, vars);
}

async function loadI18n(lang) {
    async function loadLangSet(code) {
        const [uiRes, modelsRes] = await Promise.allSettled([
            import(`./i18n/${code}/ui.js`),
            import(`./i18n/${code}/models.js`),
        ]);

        const ui = uiRes.status === "fulfilled" ? uiRes.value : (console.warn(`Missing ui i18n '${code}'`, uiRes.reason), { default: {} });
        const models = modelsRes.status === "fulfilled" ? modelsRes.value : (console.warn(`Missing models i18n '${code}'`, modelsRes.reason), { default: {} });

        return {
            ...(ui.default ?? ui),
            ...(models.default ?? models),
        };
    }

    I18N_EN = await loadLangSet("en");

    if (lang === "en") {
        I18N_CUR = I18N_EN;
        return;
    }

    const cur = await loadLangSet(lang);
    // If the language set is empty (e.g. load failed completely), fallback to EN
    if (Object.keys(cur).length === 0) {
        I18N_CUR = I18N_EN;
    } else {
        I18N_CUR = cur;
    }
}

function fmtMs(ms) {
    ms = Math.max(0, ms | 0);
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const r = s % 60;
    const mm = String(m).padStart(2, "0");
    const rr = String(r).padStart(2, "0");
    return `${mm}:${rr}`;
}

function toast(title, body, kind = "info", ttlMs = 3200) {
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

function escapeHtml(s) {
    return String(s ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function isMobileLayout() {
    return !!(window.matchMedia && window.matchMedia("(max-width: 760px)").matches);
}

function renderMarkdownMath(text, targetEl) {
    targetEl.innerHTML = md.render(text ?? "");
    // KaTeX auto-render
    try {
        window.renderMathInElement(targetEl, {
            delimiters: [
                { left: "$$", right: "$$", display: true },
                { left: "\\[", right: "\\]", display: true },
                { left: "$", right: "$", display: false },
                { left: "\\(", right: "\\)", display: false },
            ],
            throwOnError: false,
        });
    } catch {
        // ignore
    }
}

function wsUrl() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    return `${proto}//${location.host}/ws/game`;
}

class RateLimitError extends Error {
    constructor(retryAfterSeconds) {
        const secs = Number.isFinite(retryAfterSeconds) && retryAfterSeconds > 0
            ? Math.ceil(retryAfterSeconds)
            : null;

        const msg = secs
            ? t("rateLimitedMsg", { s: secs })
            : t("rateLimitedMsgNoTime");

        super(msg);
        this.name = "RateLimitError";
        this.retryAfterSeconds = secs;
    }
}

async function readErrorBody(res) {
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

function retryAfterSecondsFromHeaders(res) {
    const ra = Number(res.headers.get("Retry-After"));
    if (Number.isFinite(ra) && ra > 0) return ra;

    const resetMs = Number(res.headers.get("X-RateLimit-Reset"));
    if (Number.isFinite(resetMs) && resetMs > 0) return Math.ceil(resetMs / 1000);

    return null;
}

async function httpGetJson(path) {
    const res = await fetch(path, { credentials: "include" });
    if (!res.ok) {
        if (res.status === 429) {
            throw new RateLimitError(retryAfterSecondsFromHeaders(res));
        }
        throw new Error(await readErrorBody(res));
    }
    return await res.json();
}

function showNetError(e) {
    if (e && e.name === "RateLimitError") {
        toast(t("rateLimitedTitle"), e.message, "warn", 5200);
        return;
    }
    toast(t("toastError"), e?.message || String(e), "error");
}

function renderResultDetails(note, detailLines) {
    const el = $("#resultDetails");
    if (!el) return;

    const safeNote = String(note || "");
    const details = Array.isArray(detailLines) ? detailLines.map((x) => String(x || "")) : [];

    // cache so we can re-render on resize/orientation change
    state.ui.lastResultDetails = { note: safeNote, details };

    if (isMobileLayout()) {
        const parts = [];
        if (safeNote) {
            parts.push(`<div class="result-note">${escapeHtml(safeNote)}</div>`);
        }
        if (details.length) {
            // Prefer a single horizontal line; allow wrap that starts at the slash via <wbr>
            const joined = details.map(escapeHtml).join(" <wbr>/ ");
            parts.push(`<div class="result-inline">${joined}</div>`);
        }
        el.innerHTML = parts.join("");
        return;
    }

    const lines = [];
    if (safeNote) lines.push(safeNote);
    lines.push(...details);
    el.textContent = lines.join("\n");
}

/** ---- App State ---- */
const state = {
    playerId: null,
    issuedAt: null,

    mode: "LIGHTWEIGHT",
    models: { LIGHTWEIGHT: [], PREMIUM: [] },

    ws: null,
    wsOpen: false,

    sessionId: null,
    nickname: null,
    opponentSpecId: null,
    opponentDisplayName: null,

    players: { human: null, llm: null },

    // round
    inRound: false,
    roundId: null,
    roundNumber: 0,
    nonceToken: null,
    questionId: null,
    questionPrompt: "",
    choices: null,
    releasedAt: 0,
    handicapMs: 0,
    deadlineAt: 0,

    // answer
    selectedChoiceIndex: null,
    freeAnswerMode: "text", // "text" | "int"
    submitted: false,
    humanAnswer: null,

    // llm
    llmStatus: "IDLE",
    reasoningBuf: "",
    reasoningSeq: -1,
    reasoningEnded: false,
    reasoningSquaresShown: false,
    reasoningTruncated: false,
    lastReasoningAt: 0,
    finalAnswer: null,
    confidenceScore: null,
    reasoningSummary: null,
    streamError: null,
    roundResolveReason: null,
    reasoningRevealed: false,

    timers: {
        tickHandle: null,
        renderHandle: null,
    },

    ui: {
        reasoningPinnedToTop: true,
        programmaticScroll: false,
        matchEndVisible: false,
        lastResultDetails: null,
    },
};

/** ---- Small UI helpers ---- */
function showBottomState(which /* "pre" | "answer" | "post" */) {
    $("#preRound")?.classList.toggle("hidden", which !== "pre");
    $("#preRoundHint")?.classList.toggle("hidden", which !== "pre");
    $("#answerForm")?.classList.toggle("hidden", which !== "answer");
    $("#postRound")?.classList.toggle("hidden", which !== "post");
}

function clearOutcomeGlows() {
    const remove = (el) => el?.classList.remove("glow-win", "glow-lose", "glow-tie");
    remove($("#bottomPanel"));
    remove($("#humanChip"));
    remove($("#llmChip"));
    remove($("#llmPanel"));
    remove($("#llmStatus"));
}

function applyOutcomeGlows(winner) {
    clearOutcomeGlows();

    const humanElements = [$("#bottomPanel"), $("#humanChip")];
    const llmElements = [$("#llmPanel"), $("#llmChip"), $("#llmStatus")];

    let humanGlowClass, llmGlowClass;

    switch (winner) {
        case "HUMAN":
            humanGlowClass = "glow-win";
            llmGlowClass = "glow-lose";
            break;
        case "LLM":
            humanGlowClass = "glow-lose";
            llmGlowClass = "glow-win";
            break;
        case "TIE":
            humanGlowClass = "glow-tie";
            llmGlowClass = "glow-tie";
            break;
        default:
            return; // No glow for other cases
    }

    humanElements.forEach(el => el?.classList.add(humanGlowClass));
    llmElements.forEach(el => el?.classList.add(llmGlowClass));
}

function setSubmitFrozen(on) {
    const btn = $("#btnSubmit");
    if (!btn) return;
    btn.classList.toggle("is-frozen", !!on);
}

/** ---- UI wiring (static labels) ---- */
function initStaticText() {
    $("#btnExit").textContent = t("exit");
    $("#btnGiveUp").textContent = t("giveUp");

    document.querySelectorAll(".tab-btn").forEach((btn) => {
        const tab = btn.dataset.tab;
        if (tab === "LIGHTWEIGHT") btn.textContent = t("lightweight");
        if (tab === "PREMIUM") btn.textContent = t("premium");
        if (tab === "LEADERBOARD") btn.textContent = t("leaderboard");
    });

    $("#modeDescLight").textContent = t("descLight");
    $("#modeDescPremium").textContent = t("descPremium");

    $("#lblNicknameLight").textContent = t("nickname");
    $("#lblOpponentLight").textContent = t("opponent");
    $("#btnStartLight").textContent = t("startMatch");

    $("#lblNicknamePremium").textContent = t("nickname");
    $("#lblOpponentPremium").textContent = t("opponent");
    $("#btnStartPremium").textContent = t("startMatch");

    $("#lbTitle").textContent = t("lbTitle");
    $("#lbNote").textContent = t("lbNote");
    $("#btnRefreshLb").textContent = t("refresh");
    $("#thRank").textContent = t("thRank");
    $("#thName").textContent = t("thName");
    $("#thScore").textContent = t("thScore");
    $("#thSet").textContent = t("thSet");
    $("#thOpponent").textContent = t("thOpponent");
    $("#thMode").textContent = t("thMode");

    $("#lblDeadline").textContent = t("deadline");
    $("#lblHandicap").textContent = t("handicap");

    $("#qTitle").textContent = t("question");
    $("#rTitle").textContent = t("reasoning");
    $("#bpTitle").textContent = t("bottomPanel");
    $("#btnStartRound").textContent = t("startRound");
    $("#preRoundHint").textContent = t("preRoundHint");
    $("#btnSubmit").textContent = t("submit");
    $("#btnNext").textContent = t("next");
    $("#btnPeek").textContent = t("peek");
    $("#omitHint").textContent = t("omitted");
    $("#lblOpponentAnswer").textContent = t("oppAnswer");
    $("#choiceHintTop").textContent = t("selectionHint");
    $("#choiceHintBottom").textContent = t("selectionHint");
    $("#segText").textContent = t("answerTypeText");
    $("#segInt").textContent = t("answerTypeInt");

    $("#btnTopQuestion").textContent = t("question");
    $("#btnTopReasoning").textContent = t("reasoning");

    $("#lblEndRounds").textContent = t("matchEndRounds");
    $("#lblEndDuration").textContent = t("matchEndDuration");
    $("#btnEndLobby").textContent = t("backToLobby");
    $("#btnEndLb").textContent = t("openLeaderboard");

    $("#landingTitle").textContent = t("landingTitle");
    $("#landingDesc").textContent = t("landingDesc");
    $("#btnLandingDismiss").textContent = t("landingButton");
}

/** ---- Lobby tabs ---- */
function setLobbyTab(tab) {
    document.querySelectorAll(".tab-btn").forEach((btn) => {
        const active = btn.dataset.tab === tab;
        btn.classList.toggle("is-active", active);
        btn.setAttribute("aria-selected", active ? "true" : "false");
    });

    document.querySelectorAll(".tab-panel").forEach((panel) => {
        panel.classList.toggle("is-active", panel.id === `panel-${tab}`);
    });

    if (tab === "LIGHTWEIGHT" || tab === "PREMIUM") {
        state.mode = tab;
        populateOpponentSelects();
    }
    if (tab === "LEADERBOARD") {
        refreshLeaderboard().catch((e) => toast(t("toastError"), e.message));
    }
}

function showLobby() {
    hideMatchEndModal();
    $("#lobbyScreen").classList.add("screen-active");
    $("#gameScreen").classList.remove("screen-active");
    setGiveUpVisible(false);
}

function showGame() {
    hideMatchEndModal();
    $("#lobbyScreen").classList.remove("screen-active");
    $("#gameScreen").classList.add("screen-active");
    setGiveUpVisible(true);
}

function setGiveUpVisible(visible) {
    $("#btnGiveUp")?.classList.toggle("hidden", !visible);
}

/** ---- Session Recovery (F5) ---- */
async function tryRecoverActiveSession() {
    try {
        const res = await fetch("/api/v1/player/active-session", { credentials: "include" });
        if (res.status === 204) {
            // No active session, stay on lobby
            return false;
        }
        if (!res.ok) {
            // error - just ignore and stay on lobby
            return false;
        }
        const data = await res.json();
        if (!data.activeSessionId || !data.opponentSpecId) {
            return false;
        }

        // Try to recover display name from opponent specs
        const models = [...(state.models.LIGHTWEIGHT || []), ...(state.models.PREMIUM || [])];
        const matchingModel = models.find(m => m.id === data.opponentSpecId);
        const displayName = matchingModel?.metadata?.displayName || data.opponentSpecId;

        // We have an active session, attempt to rejoin via WebSocket
        toast(t("toastSession"), t("recovering"));

        state.sessionId = data.activeSessionId;
        state.opponentSpecId = data.opponentSpecId;
        state.opponentDisplayName = displayName;
        // Nickname is not returned by this endpoint, so we use a placeholder
        state.nickname = state.nickname || t("yourId");

        openWsAndJoin({
            sessionId: data.activeSessionId,
            opponentSpecId: data.opponentSpecId,
            nickname: state.nickname,
            locale: navigator.language || (LANG === "ja" ? "ja-JP" : "en"),
        });

        return true;
    } catch (e) {
        // Network error - ignore and stay on lobby
        console.warn("Session recovery failed:", e);
        return false;
    }
}

/** ---- Give Up (Terminate Active Session) ---- */
async function giveUp() {
    if (!confirm(t("giveUpConfirm"))) {
        return;
    }

    try {
        const res = await fetch("/api/v1/player/active-session/terminate", {
            method: "POST",
            credentials: "include",
        });
        if (!res.ok && res.status !== 204) {
            const errBody = await readErrorBody(res);
            toast(t("toastError"), errBody || t("giveUpFailed"), "error");
            return;
        }

        // Close websocket and return to lobby
        closeWs();
        showLobby();
        resetRoundUi();
        state.sessionId = null;
    } catch (e) {
        toast(t("toastError"), t("giveUpFailed"), "error");
    }
}

/** ---- Models / leaderboard ---- */
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

function updateOpponentHint() {
    const mode = state.mode;
    const hintId = mode === "LIGHTWEIGHT" ? "hintOpponentLight" : "hintOpponentPremium";
    const inputId = mode === "LIGHTWEIGHT" ? "opponentLightVal" : "opponentPremiumVal";

    const input = document.getElementById(inputId);
    const selectedId = input?.value;

    const models = state.models[mode] || [];
    const model = models.find(m => m.id === selectedId);

    const hint = document.getElementById(hintId);
    if (hint) hint.textContent = resolveModelDescription(model);
}

function populateOpponentSelects() {
    ["LIGHTWEIGHT", "PREMIUM"].forEach(mode => {
        const models = state.models[mode] || [];
        const container = $(mode === "LIGHTWEIGHT" ? "#opponentLight" : "#opponentPremium");
        const input = $(mode === "LIGHTWEIGHT" ? "#opponentLightVal" : "#opponentPremiumVal");

        if (!container) return;

        const currentVal = input?.value;
        container.innerHTML = "";

        if (!models.length) {
            container.innerHTML = `<div class="muted small" style="padding:10px;">(no models)</div>`;
            return;
        }

        let firstId = null;
        for (const m of models) {
            if (!firstId) firstId = m.id;
            container.appendChild(renderOpponentCard(m, mode));
        }

        // Auto-select first or restore
        const toSelect = (currentVal && models.find(m => m.id === currentVal)) ? currentVal : firstId;
        if (toSelect) selectOpponent(mode, toSelect);
    });
}

async function loadModels() {
    const [light, prem] = await Promise.all([
        httpGetJson("/api/v1/models?mode=LIGHTWEIGHT"),
        httpGetJson("/api/v1/models?mode=PREMIUM"),
    ]);
    state.models.LIGHTWEIGHT = light.models || [];
    state.models.PREMIUM = prem.models || [];
    populateOpponentSelects();
}

function fmtPoints(x) {
    const n = Number(x);
    if (!Number.isFinite(n)) return "0";
    return n
        .toFixed(2)
        .replace(/\.00$/, "")
        .replace(/(\.\d)0$/, "$1");
}

function lbRow(entry) {
    const tr = document.createElement("tr");
    const cells = [
        entry.rank ?? "",
        entry.nickname ?? "",
        fmtPoints(entry.bestScore ?? 0),
        entry.questionSetDisplayName ?? "",
        entry.opponentLlmName ?? "",
        entry.gameMode ?? "",
    ];
    for (const c of cells) {
        const td = document.createElement("td");
        td.textContent = String(c);
        tr.appendChild(td);
    }
    return tr;
}

async function refreshLeaderboard() {
    const data = await httpGetJson("/api/v1/leaderboard?limit=10");
    const body = $("#lbBody");
    body.innerHTML = "";
    for (const e of data.entries || []) body.appendChild(lbRow(e));
}

/** ---- Session bootstrap ---- */
async function ensurePlayerSession() {
    const data = await httpGetJson("/api/v1/player/session");
    state.playerId = data.playerId;
    state.issuedAt = data.issuedAtEpochMs;
}

/** ---- WebSocket ---- */
function setNet(ok) {
    const dot = $("#netDot");
    dot.classList.toggle("ok", !!ok);
    dot.classList.toggle("bad", !ok);
}

function closeWs() {
    try {
        state.ws?.close();
    } catch {
    }
    state.ws = null;
    state.wsOpen = false;
    setNet(false);
}

function isMatchEndVisible() {
    return !!state.ui.matchEndVisible;
}

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

function showMatchEndModal(payload) {
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

function hideMatchEndModal() {
    const overlay = $("#matchEndOverlay");
    if (!overlay) return;
    overlay.classList.add("hidden");
    overlay.setAttribute("aria-hidden", "true");
    state.ui.matchEndVisible = false;
}

function openWsAndJoin({ sessionId = null, opponentSpecId, nickname, locale }) {
    closeWs();

    const ws = new WebSocket(wsUrl());
    state.ws = ws;

    ws.addEventListener("open", () => {
        state.wsOpen = true;
        setNet(true);
        toast(t("toastSession"), t("toastNetOk"));

        const joinFrame = {
            type: "join_session",
            sessionId,
            opponentSpecId,
            nickname,
            locale: locale || navigator.language || "en",
        };
        ws.send(JSON.stringify(joinFrame));
    });

    ws.addEventListener("close", () => {
        state.wsOpen = false;
        setNet(false);
        toast(t("toastSession"), t("toastNetBad"));
    });

    ws.addEventListener("error", () => {
        state.wsOpen = false;
        setNet(false);
    });

    ws.addEventListener("message", (ev) => {
        let msg;
        try {
            msg = JSON.parse(ev.data);
        } catch {
            toast(t("toastError"), "invalid JSON from server");
            return;
        }
        handleServerEvent(msg);
    });
}

function wsSend(frame) {
    if (!state.wsOpen || !state.ws) {
        toast(t("toastError"), "websocket not connected");
        return;
    }
    state.ws.send(JSON.stringify(frame));
}

function newCommandId() {
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

/** ---- Game flow helpers ---- */
function resetRoundUi() {
    state.inRound = false;
    state.roundId = null;
    state.nonceToken = null;
    state.questionId = null;
    state.questionPrompt = "";
    state.choices = null;
    state.releasedAt = 0;
    state.handicapMs = 0;
    state.deadlineAt = 0;

    state.selectedChoiceIndex = null;
    state.submitted = false;
    state.humanAnswer = null;

    state.llmStatus = "IDLE";
    state.reasoningBuf = "";
    state.reasoningSeq = -1;
    state.reasoningEnded = false;
    state.reasoningSquaresShown = false;
    state.reasoningTruncated = false;
    state.lastReasoningAt = 0;
    state.finalAnswer = null;
    state.confidenceScore = null;
    state.reasoningSummary = null;
    state.streamError = null;
    state.roundResolveReason = null;
    state.freeAnswerMode = "text";
    state.reasoningRevealed = false;

    state.ui.reasoningPinnedToTop = true;
    const sc = getLlmScrollEl();
    if (sc) sc.scrollTop = 0;

    clearOutcomeGlows();

    state.ui.lastResultDetails = null;
    $("#omitHint").classList.add("hidden");
    $("#llmStreamError").classList.add("hidden");
    $("#llmAnswerBox").classList.add("hidden");

    $("#reasoningWrap").classList.add("masked");
    $("#reasoningBody").innerHTML = "";
    $("#llmAnswerBody").innerHTML = "";

    $("#questionBody").innerHTML = "";
    $("#qSep")?.classList.add("hidden");
    $("#qSep2")?.classList.add("hidden");
    $("#choiceHintTop")?.classList.add("hidden");
    $("#choiceHintBottom")?.classList.add("hidden");
    $("#choicesHost").innerHTML = "";

    showBottomState("pre");

    $("#aMeta").textContent = "";
    $("#qMeta").textContent = "";

    // Re-enable buttons in case a previous session disabled them
    $("#btnSubmit").disabled = false;
    $("#btnStartRound").disabled = false;
    $("#btnNext").disabled = false;
    setSubmitFrozen(false);

    updateLlmStatusPill();
    stopTimers();
    updateTimers(0);
}

function updateMatchupUi() {
    $("#humanName").textContent = state.nickname || t("yourId");
    // UX: don’t show internal IDs in the game header
    $("#humanSub").textContent = "";

    $("#llmName").textContent = state.opponentDisplayName || "LLM";
    updateLlmStatusPill();
}

function updateLlmStatusPill() {
    const el = $("#llmStatus");
    if (state.llmStatus === "IDLE") el.textContent = t("statusIdle");
    if (state.llmStatus === "THINKING") el.textContent = t("statusThinking");
    if (state.llmStatus === "ANSWERING") el.textContent = t("statusAnswering");
    if (state.llmStatus === "LOCKIN") el.textContent = t("statusLockin");
}

function startTimers() {
    stopTimers();
    state.timers.tickHandle = setInterval(() => {
        updateTimers(Date.now());
        enforceDeadline();
    }, 100);
}

function stopTimers() {
    if (state.timers.tickHandle) clearInterval(state.timers.tickHandle);
    state.timers.tickHandle = null;
}

function updateTimers(now = Date.now()) {
    const total = Math.max(1, state.deadlineAt - state.releasedAt);
    const left = Math.max(0, state.deadlineAt - now);
    $("#deadlineLeft").textContent = state.inRound ? fmtMs(left) : "--:--";
    const pct = state.inRound ? Math.max(0, Math.min(1, left / total)) : 0;
    $("#deadlineBar").style.width = `${pct * 100}%`;

    const hsTotal = Math.max(1, state.handicapMs);
    const hsLeft = Math.max(0, (state.releasedAt + state.handicapMs) - now);
    $("#handicapLeft").textContent = state.inRound ? `${Math.ceil(hsLeft / 100) / 10}s` : "--";
    const hsPct = state.inRound ? Math.max(0, Math.min(1, hsLeft / hsTotal)) : 0;
    $("#handicapBar").style.width = `${hsPct * 100}%`;
}

function enforceDeadline() {
    if (!state.inRound) return;
    if (Date.now() <= state.deadlineAt) return;
    $("#btnSubmit").disabled = true;
    $("#aMeta").textContent = t("timeUp");
}

function setTopMobileTab(which) {
    const root = $("#splitTop");
    root.classList.toggle("show-question", which === "question");
    root.classList.toggle("show-reasoning", which === "reasoning");

    $("#btnTopQuestion").classList.toggle("is-active", which === "question");
    $("#btnTopReasoning").classList.toggle("is-active", which === "reasoning");
}

function renderQuestion() {
    renderMarkdownMath(state.questionPrompt || "", $("#questionBody"));

    const rn = state.roundNumber ? `#${state.roundNumber}` : "";
    $("#qMeta").textContent = [rn, state.questionId || ""].filter(Boolean).join("  ");

    const hasChoices = Array.isArray(state.choices) && state.choices.length;
    $("#qSep")?.classList.toggle("hidden", !hasChoices);
    $("#qSep2")?.classList.toggle("hidden", !hasChoices);
    $("#choiceHintTop")?.classList.toggle("hidden", !hasChoices);
    $("#choiceHintBottom")?.classList.toggle("hidden", !hasChoices);

    const host = $("#choicesHost");
    host.innerHTML = "";
    host.classList.remove("dense");

    if (hasChoices) {
        const dense = state.choices.every(isChoiceCompact);
        host.classList.toggle("dense", dense);

        for (let i = 0; i < state.choices.length; i++) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "choice-btn";
            btn.dataset.index = String(i);

            const inner = document.createElement("div");
            inner.className = "choice-inner";
            renderMarkdownMath(state.choices[i], inner);
            btn.appendChild(inner);

            btn.addEventListener("click", () => {
                if (!state.inRound || state.submitted) return;
                state.selectedChoiceIndex = i;
                updateChoiceSelection();
            });

            host.appendChild(btn);
        }

        $("#freeAnswerType").classList.add("hidden");
        $("#freeTextWrap").classList.add("hidden");
        $("#intWrap").classList.add("hidden");

        showBottomState("answer");
        $("#btnSubmit").disabled = false;
        $("#aMeta").textContent = "";
        updateChoiceSelection();
    } else {
        // free response
        $("#choicesHost").innerHTML = "";
        showBottomState("answer");
        $("#btnSubmit").disabled = false;
        $("#aMeta").textContent = "";

        $("#freeAnswerType").classList.remove("hidden");
        applyFreeAnswerMode();
    }
}

function updateChoiceSelection() {
    document.querySelectorAll(".choice-btn").forEach((b) => {
        const idx = Number(b.dataset.index);
        b.classList.toggle("is-selected", idx === state.selectedChoiceIndex);
    });
}

function applyFreeAnswerMode() {
    $("#segText").classList.toggle("is-active", state.freeAnswerMode === "text");
    $("#segInt").classList.toggle("is-active", state.freeAnswerMode === "int");

    $("#freeTextWrap").classList.toggle("hidden", state.freeAnswerMode !== "text");
    $("#intWrap").classList.toggle("hidden", state.freeAnswerMode !== "int");
}

function scheduleReasoningRender() {
    if (state.timers.renderHandle) return;
    state.timers.renderHandle = setTimeout(() => {
        state.timers.renderHandle = null;
        renderMarkdownMath(state.reasoningBuf || "", $("#reasoningBody"));
        maybePinReasoningToTop();
    }, 90);
}

function maybeShowHiddenSquares() {
    if (!state.reasoningEnded) return;
    if (state.reasoningSquaresShown) return;

    setTimeout(() => {
        if (!state.inRound) return;
        if (!state.reasoningEnded || state.reasoningSquaresShown) return;
        if (state.reasoningRevealed) return;

        const since = Date.now() - (state.lastReasoningAt || 0);
        if (since < 700) return;

        state.reasoningSquaresShown = true;
        const blocks = "██████████████████████████ \n".repeat(16);
        state.reasoningBuf += `\n\n\`${blocks}\``;
        scheduleReasoningRender();
    }, 900);
}

function formatAnswerDisplay(ans, choicesMaybe) {
    if (!ans) return t("noAnswer");
    if (ans.type === "multiple_choice") {
        const idx = ans.choiceIndex;
        const choices = choicesMaybe;
        if (Array.isArray(choices) && choices[idx] != null) {
            const cleaned = normalizeChoiceForHeuristic(choices[idx]);
            if (cleaned) return `#${idx + 1} ${cleaned.length > 64 ? cleaned.slice(0, 64) + "…" : cleaned}`;
        }
        return `#${idx + 1}`;
    }
    if (ans.type === "integer") return String(ans.value);
    if (ans.type === "free_text") {
        const text = String(ans.text || "").trim();
        if (!text) return t("noAnswer");
        return text.length > 80 ? text.slice(0, 80) + "…" : text;
    }
    return t("noAnswer");
}

/** ---- Result strings ---- */
function fmtScore(x) {
    if (x == null) return "-";
    const n = Number(x);
    if (!Number.isFinite(n)) return "-";
    return fmtPoints(n);
}

function roundResolveLine(reason) {
    const r = String(reason || "");
    if (r === "TIMEOVER_HUMAN") return t("resolveTimeUpYou");
    if (r === "TIMEOVER_LLM") return t("resolveTimeUpOpp");
    if (r === "TIMEOVER_BOTH") return t("resolveTimeUpBoth");
    return "";
}

function sessionEndLine(reason) {
    const r = String(reason || "");
    if (r === "timeout") return t("sessionEndIdle");
    if (r === "max_lifespan") return t("sessionEndMax");
    if (r === "completed") return t("sessionEndCompleted");
    return t("sessionEndGeneric");
}

/** ---- Server event handler ---- */
function handleServerEvent(msg) {
    const type = msg.type;

    if (type === "session_error") {
        const code = String(msg.errorCode || "");
        if (code === "rate_limited") {
            toast(t("rateLimitedTitle"), msg.message || t("rateLimitedMsgNoTime"), "warn", 5200);
        } else if (code === "session_limit_exceeded") {
            toast(t("rateLimitedTitle"), msg.message || t("rateLimitedMsgNoTime"), "warn", 5200);
        } else {
            toast(t("toastError"), `${code}: ${msg.message || ""}`, "error");
        }
        return;
    }

    if (type === "session_resolved") {
        state.inRound = false;
        stopTimers();
        $("#btnSubmit").disabled = true;
        $("#btnStartRound").disabled = true;
        $("#btnNext").disabled = true;

        showMatchEndModal({
            sessionId: msg.sessionId,
            state: msg.state,
            reason: msg.reason,
            humanTotalScore: msg.humanTotalScore,
            llmTotalScore: msg.llmTotalScore,
            winner: msg.winner,
            roundsPlayed: msg.roundsPlayed,
            totalRounds: msg.totalRounds,
            durationMs: msg.durationMs,
        });
        return;
    }

    if (type === "session_joined") {
        state.sessionId = msg.sessionId;
        location.hash = `session=${encodeURIComponent(state.sessionId)}`;

        showGame();
        resetRoundUi();
        updateMatchupUi();
        return;
    }

    if (type === "player_joined") {
        if (msg.playerId === state.playerId) {
            state.players.human = { playerId: msg.playerId, nickname: msg.nickname };
        } else {
            state.players.llm = { playerId: msg.playerId, nickname: msg.nickname };
        }
        updateMatchupUi();
        return;
    }

    if (type === "round_started") {
        resetRoundUi();
        state.inRound = true;

        state.sessionId = msg.sessionId;
        state.roundId = msg.roundId;
        state.roundNumber = msg.roundNumber;
        state.questionId = msg.questionId;
        state.questionPrompt = msg.questionPrompt || "";
        state.choices = msg.choices ?? null;

        if (msg.expectedAnswerType === "integer") {
            state.freeAnswerMode = "int";
        } else if (msg.expectedAnswerType === "free_text") {
            state.freeAnswerMode = "text";
        }

        state.releasedAt = msg.releasedAtEpochMs || Date.now();
        state.handicapMs = msg.handicapMs || 0;
        state.deadlineAt = msg.deadlineAtEpochMs || (state.releasedAt + 60000);
        state.nonceToken = msg.nonceToken;

        state.llmStatus = "IDLE";
        updateMatchupUi();

        renderQuestion();
        startTimers();
        updateTimers(Date.now());
        enforceDeadline(); // In case of clock skew, immediately lock if already past deadline
        return;
    }

    if (type === "llm_thinking") {
        if (msg.roundId !== state.roundId) return;
        state.llmStatus = "THINKING";
        updateLlmStatusPill();
        return;
    }

    if (type === "llm_reasoning_delta") {
        if (msg.roundId !== state.roundId) return;

        if (typeof msg.seq === "number" && msg.seq <= state.reasoningSeq) return;
        state.reasoningSeq = msg.seq ?? state.reasoningSeq;

        state.reasoningBuf += msg.deltaText || "";
        state.lastReasoningAt = Date.now();

        if (state.llmStatus === "IDLE") {
            state.llmStatus = "THINKING";
            updateLlmStatusPill();
        }

        scheduleReasoningRender();
        return;
    }

    if (type === "llm_reasoning_truncated") {
        if (msg.roundId !== state.roundId) return;
        state.reasoningTruncated = true;
        $("#omitHint").classList.remove("hidden");
        return;
    }

    if (type === "llm_reasoning_ended") {
        if (msg.roundId !== state.roundId) return;
        state.reasoningEnded = true;
        state.llmStatus = "ANSWERING";
        updateLlmStatusPill();
        maybeShowHiddenSquares();
        return;
    }

    if (type === "llm_answer_lock_in") {
        if (msg.roundId !== state.roundId) return;
        state.llmStatus = "LOCKIN";
        updateLlmStatusPill();
        return;
    }

    if (type === "llm_final_answer") {
        if (msg.roundId !== state.roundId) return;

        // Always store the answer data - we need it for round_resolved display
        state.finalAnswer = msg.finalAnswer || null;
        state.confidenceScore = typeof msg.confidenceScore === "number" ? msg.confidenceScore : null;
        state.reasoningSummary = msg.reasoningSummary || null;

        // Defer UI update if the round is still in progress and human hasn't submitted
        // (anti-cheat: don't spoil the answer before human commits)
        // When round_resolved arrives, it will use state.finalAnswer for display
        if (state.inRound && !state.submitted) {
            return;
        }

        // Display the answer box
        showLlmAnswerBox();
        return;
    }

    if (type === "llm_stream_error") {
        if (msg.roundId !== state.roundId) return;
        state.streamError = msg.message || "stream error";
        const box = $("#llmStreamError");
        box.textContent = state.streamError;
        box.classList.remove("hidden");
        return;
    }

    if (type === "round_resolved") {
        if (msg.roundId !== state.roundId) return;

        state.roundResolveReason = msg.reason || null;

        state.inRound = false;
        stopTimers();
        $("#btnSubmit").disabled = true;
        setSubmitFrozen(false);

        applyOutcomeGlows(msg.winner);

        showBottomState("post");
        $("#btnNext").disabled = false;

        // Show the LLM answer box if we have a stored answer that wasn't displayed yet
        // (this handles the Human Timeout case where llm_final_answer was deferred)
        if (state.finalAnswer) {
            showLlmAnswerBox();
        }

        // highlight correct choice if multiple-choice
        if (msg.correctAnswer?.type === "multiple_choice" && Array.isArray(state.choices)) {
            const correct = msg.correctAnswer.choiceIndex;
            document.querySelectorAll(".choice-btn").forEach((b) => {
                const idx = Number(b.dataset.index);
                if (idx === correct) b.classList.add("is-correct");
                if (state.humanAnswer?.type === "multiple_choice" && idx === state.humanAnswer.choiceIndex && idx !== correct) {
                    b.classList.add("is-wrong");
                }
            });
        }

        const lines = [];
        const note = roundResolveLine(msg.reason);

        const hMark = msg.humanCorrect === true ? " ✅" : (msg.humanCorrect === false ? " ❌" : "");
        const lMark = msg.llmCorrect === true ? " ✅" : (msg.llmCorrect === false ? " ❌" : "");

        if (msg.humanScore != null || msg.llmScore != null) {
            lines.push(`${t("roundScore")}: ${fmtScore(msg.humanScore)} - ${fmtScore(msg.llmScore)}`);
        }

        lines.push(`${t("correctAnswerLabel")}: ${formatAnswerDisplay(msg.correctAnswer, state.choices)}`);
        lines.push(`${t("yourAnswerLabel")}${hMark}: ${state.humanAnswer ? formatAnswerDisplay(state.humanAnswer, state.choices) : t("noAnswer")}`);
        lines.push(`${t("oppAnswerLabel")}${lMark}: ${state.finalAnswer ? formatAnswerDisplay(state.finalAnswer, state.choices) : (msg.reason === "TIMEOVER_LLM" ? t("noAnswer") : t("oppPending"))}`);

        renderResultDetails(note, lines);
        return;
    }

    if (type === "llm_reasoning_reveal") {
        if (msg.roundId !== state.roundId) return;
        state.reasoningRevealed = true;
        state.reasoningBuf = msg.fullReasoning || "";
        scheduleReasoningRender();
        $("#reasoningWrap").classList.remove("masked");
        return;
    }

    if (type === "session_terminated") {
        if (isMatchEndVisible()) {
            closeWs();

        } else {
            const line = sessionEndLine(msg.reason || "");
            toast(t("toastSession"), line);
            closeWs();
            showLobby();
            resetRoundUi();

        }
    }
}

/** Helper to display the LLM answer box (extracted for reuse) */
function showLlmAnswerBox() {
    $("#llmAnswerBox").classList.remove("hidden");

    const conf = state.confidenceScore == null ? "" : `${t("confidence")}: ${(state.confidenceScore * 100).toFixed(0)}%`;
    $("#lblConfidence").textContent = conf;

    const body = $("#llmAnswerBody");
    const fa = state.finalAnswer;

    if (!fa) {
        renderMarkdownMath("_no answer_", body);
    } else if (fa.type === "multiple_choice") {
        const idx = fa.choiceIndex;
        const txt = (Array.isArray(state.choices) && state.choices[idx] != null) ? state.choices[idx] : `choice #${idx}`;
        renderMarkdownMath(`**${escapeMarkdownInline(txt)}**`, body);
    } else if (fa.type === "integer") {
        renderMarkdownMath(`**${fa.value}**`, body);
    } else if (fa.type === "free_text") {
        renderMarkdownMath(fa.text || "", body);
    } else {
        renderMarkdownMath("_(unknown answer type)_", body);
    }

    state.ui.reasoningPinnedToTop = false;
    scrollLlmPanelToBottom();
}

function escapeMarkdownInline(s) {
    return String(s ?? "").replaceAll("*", "\\*").replaceAll("_", "\\_").replaceAll("`", "\\`");
}

/** ---- Actions ---- */
function startMatch(mode) {
    const nickname = (mode === "LIGHTWEIGHT" ? $("#nicknameLight").value : $("#nicknamePremium").value).trim();
    // Use the hidden input for value
    const valInput = (mode === "LIGHTWEIGHT" ? $("#opponentLightVal") : $("#opponentPremiumVal"));
    const opponentSpecId = valInput.value;

    if (!nickname) {
        toast(t("toastError"), `${t("nickname")} is required`, "error");
        return;
    }
    if (nickname.length > MAX_NICKNAME_LEN) {
        toast(t("toastError"), t("nicknameTooLong", { n: MAX_NICKNAME_LEN }), "error");
        return;
    }
    for (const ch of nickname) {
        if (/[\u0000-\u001F\u007F]/.test(ch)) {
            toast(t("toastError"), t("nicknameInvalidChars"), "error");
            return;
        }
    }
    if (!opponentSpecId) {
        toast(t("toastError"), `${t("opponent")} is required`, "error");
        return;
    }

    const models = state.models[mode] || [];
    const selectedModel = models.find(m => m.id === opponentSpecId);
    const displayName = selectedModel?.metadata?.displayName || selectedModel?.id || "LLM";

    state.mode = mode;
    state.nickname = nickname;
    state.opponentSpecId = opponentSpecId;
    state.opponentDisplayName = displayName;

    safeLsSet(STORAGE_KEY_NICKNAME, nickname);

    updateMatchupUi();

    openWsAndJoin({
        sessionId: null,
        opponentSpecId,
        nickname,
        locale: navigator.language || (LANG === "ja" ? "ja-JP" : "en"),
    });
}

function startRound() {
    if (!state.sessionId) {
        toast(t("toastError"), "no sessionId");
        return;
    }
    wsSend({
        type: "start_round_request",
        sessionId: state.sessionId,
        playerId: state.playerId, // server validates with cookie identity
        commandId: newCommandId(),
    });
}

function submitAnswer() {
    if (!state.roundId || !state.sessionId || !state.nonceToken) {
        toast(t("toastError"), "round not ready");
        return;
    }
    if (Date.now() > state.deadlineAt) {
        enforceDeadline();
        return;
    }
    if (state.submitted) return;

    let answer;
    if (Array.isArray(state.choices) && state.choices.length) {
        if (state.selectedChoiceIndex == null) {
            toast(t("toastError"), "choose one option");
            return;
        }
        answer = { type: "multiple_choice", choiceIndex: state.selectedChoiceIndex };
    } else {
        if (state.freeAnswerMode === "int") {
            const raw = $("#intValue").value.trim();
            if (!raw || !/^-?\d+$/.test(raw)) {
                toast(t("toastError"), "enter an integer");
                return;
            }
            answer = { type: "integer", value: parseInt(raw, 10) };
        } else {
            const text = $("#freeText").value.trim();
            if (!text) {
                toast(t("toastError"), "enter text");
                return;
            }
            answer = { type: "free_text", text };
        }
    }

    state.submitted = true;
    state.humanAnswer = answer;
    $("#btnSubmit").disabled = true;
    setSubmitFrozen(true);
    $("#aMeta").textContent = t("submitted");

    wsSend({
        type: "submit_answer",
        sessionId: state.sessionId,
        playerId: state.playerId,
        roundId: state.roundId,
        commandId: newCommandId(),
        nonceToken: state.nonceToken,
        answer,
        clientSentAtEpochMs: Date.now(),
    });
}

function goNext() {
    resetRoundUi();
    startRound();
}

/** ---- Init ---- */
function bindUi() {
    document.querySelectorAll(".tab-btn").forEach((btn) => {
        btn.addEventListener("click", () => setLobbyTab(btn.dataset.tab));
    });

    $("#btnLandingDismiss").addEventListener("click", () => {
        $("#landingOverlay").classList.add("hidden");
        $("#landingOverlay").setAttribute("aria-hidden", "true");
        localStorage.setItem(STORAGE_KEY_LANDING, "true");
    });

    $("#btnStartLight").addEventListener("click", () => startMatch("LIGHTWEIGHT"));
    $("#btnStartPremium").addEventListener("click", () => startMatch("PREMIUM"));

    $("#btnRefreshLb").addEventListener("click", () => refreshLeaderboard().catch(showNetError));

    $("#btnExit").addEventListener("click", () => {
        hideMatchEndModal();
        closeWs();
        showLobby();
        resetRoundUi();
    });

    $("#btnGiveUp").addEventListener("click", giveUp);

    $("#btnEndLobby").addEventListener("click", () => {
        hideMatchEndModal();
        closeWs();
        showLobby();
        resetRoundUi();
    });
    $("#btnEndLb").addEventListener("click", () => {
        hideMatchEndModal();
        closeWs();
        showLobby();
        setLobbyTab("LEADERBOARD");
        resetRoundUi();
    });

    $("#btnStartRound").addEventListener("click", startRound);
    $("#btnSubmit").addEventListener("click", submitAnswer);
    $("#btnNext").addEventListener("click", goNext);

    $("#btnPeek").addEventListener("click", () => {
        $("#reasoningWrap").classList.remove("masked");
    });

    $("#btnTopQuestion").addEventListener("click", () => setTopMobileTab("question"));
    $("#btnTopReasoning").addEventListener("click", () => setTopMobileTab("reasoning"));

    $("#matchEndOverlay").addEventListener("click", (e) => {
        if (e.target && e.target.id === "matchEndOverlay") {
            // no-op
        }
    });

    $("#segText").addEventListener("click", () => {
        state.freeAnswerMode = "text";
        applyFreeAnswerMode();
    });
    $("#segInt").addEventListener("click", () => {
        state.freeAnswerMode = "int";
        applyFreeAnswerMode();
    });

    // Select lists are interactive now, no change events from the container
    // Logic handled in selectOpponent

    $("#freeText").addEventListener("keydown", (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === "Enter") submitAnswer();
    });
    $("#intValue").addEventListener("keydown", (e) => {
        if (e.key === "Enter") submitAnswer();
    });

    $("#btnLicense")?.addEventListener("click", showLicenseModal);
    $("#btnCloseLicense")?.addEventListener("click", hideLicenseModal);
    $("#btnLicenseOk")?.addEventListener("click", hideLicenseModal);

    $("#licenseOverlay")?.addEventListener("click", (e) => {
        if (e.target && e.target.id === "licenseOverlay") hideLicenseModal();
    });

    window.addEventListener("keydown", (e) => {
        const overlay = $("#licenseOverlay");
        const open = overlay && !overlay.classList.contains("hidden");
        if (open && e.key === "Escape") hideLicenseModal();
    });


    const llmScroll = getLlmScrollEl();
    if (llmScroll) {
        llmScroll.addEventListener("scroll", () => {
            if (state.ui.programmaticScroll) return;
            state.ui.reasoningPinnedToTop = llmScroll.scrollTop <= 2;
        }, { passive: true });
    }

    // Re-render result details when crossing the responsive breakpoint (e.g., rotation)
    let resizeRaf = 0;
    window.addEventListener("resize", () => {
        if (!state.ui.lastResultDetails) return;
        if (resizeRaf) cancelAnimationFrame(resizeRaf);
        resizeRaf = requestAnimationFrame(() => {
            const { note, details } = state.ui.lastResultDetails || {};
            renderResultDetails(note, details);
        });
    }, { passive: true });
}

/** ---- LICENSE modal ---- */
let lastFocusEl = null;

function showLicenseModal() {
    const overlay = $("#licenseOverlay");
    if (!overlay) return;

    lastFocusEl = document.activeElement;

    overlay.classList.remove("hidden");
    overlay.setAttribute("aria-hidden", "false");

    requestAnimationFrame(() => {
        $("#btnCloseLicense")?.focus();
    });
}

function hideLicenseModal() {
    const overlay = $("#licenseOverlay");
    if (!overlay) return;

    overlay.classList.add("hidden");
    overlay.setAttribute("aria-hidden", "true");

    if (lastFocusEl && typeof lastFocusEl.focus === "function") {
        lastFocusEl.focus();
    }
    lastFocusEl = null;
}

function normalizeChoiceForHeuristic(s) {
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

function isChoiceCompact(src) {
    const raw = String(src ?? "");

    if (raw.includes("\n")) return false;
    if (raw.includes("```")) return false;
    if (raw.includes("|")) return false;
    if (raw.includes("$$") || raw.includes("\\[")) return false;

    const norm = normalizeChoiceForHeuristic(raw);
    return norm.length > 0 && norm.length <= 42;
}

function getLlmScrollEl() {
    return $("#llmPanel .panel-body");
}

function maybePinReasoningToTop() {
    const sc = getLlmScrollEl();
    if (!sc) return;
    if (!state.ui.reasoningPinnedToTop) return;

    state.ui.programmaticScroll = true;
    sc.scrollTop = 0;
    requestAnimationFrame(() => {
        state.ui.programmaticScroll = false;
    });
}

function scrollLlmPanelToBottom() {
    const sc = $("#llmPanel .panel-body");
    if (!sc) return;

    state.ui.programmaticScroll = true;
    sc.scrollTop = sc.scrollHeight;

    requestAnimationFrame(() => {
        sc.scrollTop = sc.scrollHeight;
        state.ui.programmaticScroll = false;
    });
}

async function loadLicenseHtml() {
    const host = document.getElementById('licenseContent');
    if (!host) return;

    try {
        const [resDs, resFe] = await Promise.all([
            fetch('./license-dataset.html', { cache: 'no-cache' }),
            fetch('./license-frontend.html', { cache: 'no-cache' })
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

function checkLandingPopup() {
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

function safeLsGet(key) {
    try { return localStorage.getItem(key); } catch { return null; }
}
function safeLsSet(key, val) {
    try { localStorage.setItem(key, val); return true; } catch { return false; }
}

async function main() {
    await loadI18n(LANG);
    initStaticText();

    // Load saved nickname
    const savedNick = localStorage.getItem(STORAGE_KEY_NICKNAME);
    if (savedNick) {
        state.nickname = savedNick;
        const nl = $("#nicknameLight");
        const np = $("#nicknamePremium");
        if (nl) nl.value = savedNick;
        if (np) np.value = savedNick;
    }

    bindUi();
    setNet(false);
    resetRoundUi();

    setLobbyTab("LIGHTWEIGHT");
    await loadLicenseHtml();
    checkLandingPopup();

    try {
        await ensurePlayerSession();
        await loadModels();

        // Attempt to recover an active session (e.g., after F5 refresh)
        await tryRecoverActiveSession();
    } catch (e) {
        showNetError(e);
    }
}

main().catch((e) => toast(t("toastError"), e.message));
