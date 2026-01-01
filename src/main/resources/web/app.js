/* app.js - Vanilla JS frontend for LMVU (Ktor same-origin) */

const $ = (sel, root = document) => root.querySelector(sel);

const md = window.markdownit({
    html: false,
    linkify: true,
    breaks: true,
});

const MAX_NICKNAME_LEN = 16;

function detectLang() {
    const raw = (navigator.language || "en").toLowerCase();
    if (raw.startsWith("ja")) return "ja";
    return "en";
}

const LANG = detectLang();

const I18N = {
    en: {
        exit: "Exit",
        lightweight: "LIGHTWEIGHT",
        premium: "PREMIUM",
        leaderboard: "Leaderboard",
        descLight:
            "Lightweight is a match against a pre-prepared replay. After the question appears, the LLM has a small handicap delay before it starts answering. The final part of its reasoning is not visible.",
        descPremium:
            "Premium is a real-time match against an LLM via API. The final part of its reasoning is not visible.",
        nickname: "Nickname",
        opponent: "Opponent",
        startMatch: "Start match",
        health: "Heartbeat",
        lbTitle: "Top players",
        lbNote: "In-memory leaderboard (resets on server restart).",
        refresh: "Refresh",
        thRank: "#",
        thName: "Name",
        thScore: "Best score",
        thSet: "Question set",
        thOpponent: "Opponent",
        thMode: "Mode",
        startRound: "Start round",
        preRoundHint: "You can start the next round when ready.",
        question: "Question",
        reasoning: "LLM",
        answer: "Your answer",
        bottomPanel: "Controls",
        submit: "Submit",
        next: "Next",
        deadline: "Deadline",
        handicap: "Handicap",
        peek: "Peek 👀 (reveal hidden reasoning)",
        omitted: "Earlier reasoning omitted",
        oppAnswer: "Opponent answer",
        confidence: "confidence",
        statusIdle: "IDLE",
        statusThinking: "THINKING",
        statusAnswering: "ANSWERING",
        statusLockin: "LOCKIN!",
        answerTypeText: "Text",
        answerTypeInt: "Integer",
        toastNetOk: "Connected",
        toastNetBad: "Disconnected",
        toastSession: "Session",
        toastError: "Error",
        timeUp: "Time is up. Submission closed.",
        submitted: "Submitted!",

        // round resolution (game-like, not enum-y)
        resolveTimeUpYou: "Time’s up — you didn’t answer in time.",
        resolveTimeUpOpp: "Time’s up — your opponent missed the timer.",
        resolveTimeUpBoth: "Time’s up — no one answered.",

        // result lines
        roundScore: "Round score",
        correctAnswerLabel: "Correct",
        yourAnswerLabel: "You",
        oppAnswerLabel: "Opponent",
        oppPending: "…revealing…",
        noAnswer: "—",

        // session end reasons
        sessionEndIdle: "Session ended (idle).",
        sessionEndMax: "Session ended (time limit).",
        sessionEndCompleted: "Match complete.",
        sessionEndGeneric: "Session ended.",
        winnerHuman: "You win!",
        winnerLLM: "You lose.",
        winnerTie: "Tie.",
        winnerNone: "No winner.",
        yourId: "You",

        // match end popup
        matchEndWin: "Victory!",
        matchEndLose: "Defeat",
        matchEndTie: "Draw",
        matchEndNone: "Match ended",
        matchEndSubCompleted: "All rounds are in. Final results!",
        matchEndSubTimeout: "The match timed out.",
        matchEndSubMax: "Time limit reached.",
        matchEndSubCancelled: "Match cancelled.",
        matchEndRounds: "Rounds",
        matchEndDuration: "Duration",
        backToLobby: "Back to lobby",
        openLeaderboard: "Leaderboard",

        // validation / rate limit
        nicknameTooLong: `Nickname must be at most ${MAX_NICKNAME_LEN} characters.`,
        nicknameInvalidChars: "Nickname contains invalid characters.",
        rateLimitedTitle: "Rate limit",
        rateLimitedMsg: "Too many requests. Try again in {s}s.",
        rateLimitedMsgNoTime: "Too many requests. Please try again shortly.",
    },
    ja: {
        exit: "戻る",
        lightweight: "LIGHTWEIGHT",
        premium: "PREMIUM",
        leaderboard: "Leaderboard",
        descLight:
            "Lightweightは事前に準備されたリプレイファイルとの対戦です…問題が表示されてからLLMが回答するまでには少しハンディキャップ時間があります。また、Reasoningのうち最終盤は見ることはできません。",
        descPremium:
            "PremiumはAPI経由で実際にLLMとリアルタイムで対戦します…Reasoningのうち最終盤は見ることはできません。",
        nickname: "ニックネーム",
        opponent: "対戦相手",
        startMatch: "対戦開始",
        health: "Heartbeat",
        lbTitle: "Top players",
        lbNote: "インメモリのためサーバ再起動でリセットされます。",
        refresh: "更新",
        thRank: "#",
        thName: "Name",
        thScore: "Best score",
        thSet: "問題セット",
        thOpponent: "Opponent",
        thMode: "Mode",
        startRound: "次のラウンドを開始",
        preRoundHint: "準備ができたら開始できます。",
        question: "問題",
        reasoning: "LLM",
        answer: "あなたの回答",
        bottomPanel: "操作パネル",
        submit: "送信",
        next: "次へ",
        deadline: "締切",
        handicap: "ハンディキャップ",
        peek: "覗き見しちゃう 👀（隠しReasoningを表示）",
        omitted: "序盤のReasoningが省略されています",
        oppAnswer: "相手の回答",
        confidence: "自信度",
        statusIdle: "IDLE",
        statusThinking: "THINKING",
        statusAnswering: "ANSWERING",
        statusLockin: "LOCKIN!",
        answerTypeText: "テキスト",
        answerTypeInt: "整数",
        toastNetOk: "接続中",
        toastNetBad: "切断",
        toastSession: "セッション",
        toastError: "エラー",
        timeUp: "締切を過ぎました。送信できません。",
        submitted: "送信しました！",

        // round resolution (game-like, not enum-y)
        resolveTimeUpYou: "時間切れ…あなたの回答が間に合いませんでした。",
        resolveTimeUpOpp: "時間切れ…相手の回答が間に合いませんでした。",
        resolveTimeUpBoth: "時間切れ…誰も回答できませんでした。",

        // result lines
        roundScore: "このラウンドのスコア",
        correctAnswerLabel: "正解",
        yourAnswerLabel: "あなた",
        oppAnswerLabel: "相手",
        oppPending: "…表示中…",
        noAnswer: "—",

        // session end reasons
        sessionEndIdle: "セッション終了（放置）。",
        sessionEndMax: "セッション終了（時間上限）。",
        sessionEndCompleted: "対戦終了！",
        sessionEndGeneric: "セッション終了。",
        winnerHuman: "あなたの勝ち！",
        winnerLLM: "負け。",
        winnerTie: "引き分け。",
        winnerNone: "勝者なし。",
        yourId: "あなた",

        // match end popup
        matchEndWin: "勝利！",
        matchEndLose: "敗北",
        matchEndTie: "引き分け",
        matchEndNone: "対戦終了",
        matchEndSubCompleted: "全ラウンド終了！最終結果です。",
        matchEndSubTimeout: "時間切れで終了しました。",
        matchEndSubMax: "時間上限に達しました。",
        matchEndSubCancelled: "対戦をキャンセルしました。",
        matchEndRounds: "ラウンド",
        matchEndDuration: "所要時間",
        backToLobby: "ロビーへ戻る",
        openLeaderboard: "ランキング",

        // validation / rate limit
        nicknameTooLong: `ニックネームは${MAX_NICKNAME_LEN}文字以内にしてください。`,
        nicknameInvalidChars: "ニックネームに使用できない文字が含まれています。",
        rateLimitedTitle: "レート制限",
        rateLimitedMsg: "リクエストが多すぎます。{s}秒後に試してください。",
        rateLimitedMsgNoTime: "リクエストが多すぎます。少し待ってから試してください。",
    }
};

function t(key) {
    return (I18N[LANG] && I18N[LANG][key]) || I18N.en[key] || key;
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
    <div class="t-title">${escapeHtml(title)}</div>
    <div class="t-body">${escapeHtml(body)}</div>
  `;
    host.appendChild(el);
    setTimeout(() => el.remove(), ttlMs);
}

function escapeHtml(s) {
    return String(s ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
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
            ? t("rateLimitedMsg").replace("{s}", String(secs))
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

    timers: {
        tickHandle: null,
        renderHandle: null,
    },

    ui: {
        reasoningPinnedToTop: true,
        programmaticScroll: false,
        matchEndVisible: false,
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
    $("#segText").textContent = t("answerTypeText");
    $("#segInt").textContent = t("answerTypeInt");

    $("#btnTopQuestion").textContent = t("question");
    $("#btnTopReasoning").textContent = t("reasoning");

    $("#lblEndRounds").textContent = t("matchEndRounds");
    $("#lblEndDuration").textContent = t("matchEndDuration");
    $("#btnEndLobby").textContent = t("backToLobby");
    $("#btnEndLb").textContent = t("openLeaderboard");
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
}

function showGame() {
    hideMatchEndModal();
    $("#lobbyScreen").classList.remove("screen-active");
    $("#gameScreen").classList.add("screen-active");
}

/** ---- Models / leaderboard ---- */
function populateOpponentSelects() {
    const models = state.models[state.mode] || [];
    const sel = state.mode === "LIGHTWEIGHT" ? $("#opponentLight") : $("#opponentPremium");
    const hint = state.mode === "LIGHTWEIGHT" ? $("#hintOpponentLight") : $("#hintOpponentPremium");

    sel.innerHTML = "";
    if (!models.length) {
        const opt = document.createElement("option");
        opt.value = "";
        opt.textContent = "(no models)";
        sel.appendChild(opt);
        hint.textContent = "";
        return;
    }

    for (const m of models) {
        const opt = document.createElement("option");
        opt.value = m.id;
        opt.textContent = m.displayName || m.llmProfile?.displayName || m.id;
        opt.dataset.displayName = opt.textContent;
        sel.appendChild(opt);
    }
    hint.textContent = "";
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
    try { state.ws?.close(); } catch {}
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
        try { msg = JSON.parse(ev.data); } catch {
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

    state.ui.reasoningPinnedToTop = true;
    const sc = getLlmScrollEl();
    if (sc) sc.scrollTop = 0;

    clearOutcomeGlows();

    $("#omitHint").classList.add("hidden");
    $("#llmStreamError").classList.add("hidden");
    $("#llmAnswerBox").classList.add("hidden");

    $("#reasoningWrap").classList.add("masked");
    $("#reasoningBody").innerHTML = "";
    $("#llmAnswerBody").innerHTML = "";

    $("#questionBody").innerHTML = "";
    $("#qSep")?.classList.add("hidden");
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
        if (!state.reasoningEnded || state.reasoningSquaresShown) return;

        const since = Date.now() - (state.lastReasoningAt || 0);
        if (since < 700) return;

        state.reasoningSquaresShown = true;
        const blocks = "■".repeat(64);
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
    if (r === "idle_timeout") return t("sessionEndIdle");
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

        if (state.inRound && !state.submitted) {
            return;
        }

        state.finalAnswer = msg.finalAnswer || null;
        state.confidenceScore = typeof msg.confidenceScore === "number" ? msg.confidenceScore : null;
        state.reasoningSummary = msg.reasoningSummary || null;

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
        if (note) lines.push(note);

        if (msg.humanScore != null || msg.llmScore != null) {
            lines.push(`${t("roundScore")}: ${fmtScore(msg.humanScore)} - ${fmtScore(msg.llmScore)}`);
        }

        lines.push(`${t("correctAnswerLabel")}: ${formatAnswerDisplay(msg.correctAnswer, state.choices)}`);
        lines.push(`${t("yourAnswerLabel")}: ${state.humanAnswer ? formatAnswerDisplay(state.humanAnswer, state.choices) : t("noAnswer")}`);
        lines.push(`${t("oppAnswerLabel")}: ${state.finalAnswer ? formatAnswerDisplay(state.finalAnswer, state.choices) : (msg.reason === "TIMEOVER_LLM" ? t("noAnswer") : t("oppPending"))}`);

        $("#resultDetails").textContent = lines.join("\n");
        return;
    }

    if (type === "llm_reasoning_reveal") {
        if (msg.roundId !== state.roundId) return;
        state.reasoningBuf = msg.fullReasoning || "";
        scheduleReasoningRender();
        $("#reasoningWrap").classList.remove("masked");
        return;
    }

    if (type === "session_terminated") {
        if (isMatchEndVisible()) {
            closeWs();
            return;
        } else {
            const line = sessionEndLine(msg.reason || "");
            toast(t("toastSession"), line);
            closeWs();
            showLobby();
            resetRoundUi();
            return;
        }
    }
}

function escapeMarkdownInline(s) {
    return String(s ?? "").replaceAll("*", "\\*").replaceAll("_", "\\_").replaceAll("`", "\\`");
}

/** ---- Actions ---- */
function startMatch(mode) {
    const nickname = (mode === "LIGHTWEIGHT" ? $("#nicknameLight").value : $("#nicknamePremium").value).trim();
    const sel = (mode === "LIGHTWEIGHT" ? $("#opponentLight") : $("#opponentPremium"));
    const opponentSpecId = sel.value;

    if (!nickname) {
        toast(t("toastError"), `${t("nickname")} is required`, "error");
        return;
    }
    if (nickname.length > MAX_NICKNAME_LEN) {
        toast(t("toastError"), t("nicknameTooLong"), "error");
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

    const displayName = sel.selectedOptions?.[0]?.dataset?.displayName || sel.selectedOptions?.[0]?.textContent || "LLM";

    state.mode = mode;
    state.nickname = nickname;
    state.opponentSpecId = opponentSpecId;
    state.opponentDisplayName = displayName;

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

    $("#btnStartLight").addEventListener("click", () => startMatch("LIGHTWEIGHT"));
    $("#btnStartPremium").addEventListener("click", () => startMatch("PREMIUM"));

    $("#btnRefreshLb").addEventListener("click", () => refreshLeaderboard().catch(showNetError));

    $("#btnExit").addEventListener("click", () => {
        hideMatchEndModal();
        closeWs();
        showLobby();
        resetRoundUi();
    });

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

    $("#opponentLight").addEventListener("change", () => {
        const opt = $("#opponentLight").selectedOptions?.[0];
        $("#hintOpponentLight").textContent = opt ? opt.textContent : "";
    });
    $("#opponentPremium").addEventListener("change", () => {
        const opt = $("#opponentPremium").selectedOptions?.[0];
        $("#hintOpponentPremium").textContent = opt ? opt.textContent : "";
    });

    $("#freeText").addEventListener("keydown", (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === "Enter") submitAnswer();
    });
    $("#intValue").addEventListener("keydown", (e) => {
        if (e.key === "Enter") submitAnswer();
    });

    const llmScroll = getLlmScrollEl();
    if (llmScroll) {
        llmScroll.addEventListener("scroll", () => {
            if (state.ui.programmaticScroll) return;
            state.ui.reasoningPinnedToTop = llmScroll.scrollTop <= 2;
        }, { passive: true });
    }
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

async function main() {
    initStaticText();
    bindUi();
    setNet(false);
    resetRoundUi();

    setLobbyTab("LIGHTWEIGHT");

    try {
        await ensurePlayerSession();
        await loadModels();
    } catch (e) {
        showNetError(e);
    }
}

main().catch((e) => toast(t("toastError"), e.message));
