import { $ } from "../core/dom.js";
import { httpGetJson } from "../core/net.js";
import { fmtPoints } from "../core/utils.js";
import { t } from "../core/i18n.js";

const PAGE_LIMIT = 50;

let cachedEntries = [];
let currentPageIndex = 0;
let listenersAttached = false;
let resizeRaf = 0;
let lastPageSize = getPageSize();

function lbRow(entry) {
    const tr = document.createElement("tr");
    const cells = [
        entry.rank ?? "",
        entry.nickname ?? "",
        fmtPoints(entry.humanFinalScore ?? 0),
        fmtPoints(entry.llmFinalScore ?? 0),
        entry.questionSetDisplayName ?? "",
        entry.opponentLlmName ?? "",
        entry.gameMode ?? "",
    ];
    cells.forEach((c, i) => {
        const td = document.createElement("td");
        td.textContent = String(c);
        if (i === 0) td.classList.add("rank-cell");
        tr.appendChild(td);
    });
    return tr;
}

function getPageSize() {
    const width = window.innerWidth || 0;
    if (width >= 1200) return 12;
    if (width >= 1000) return 10;
    if (width >= 820) return 8;
    if (width >= 680) return 6;
    return 4;
}

function getOpponentFilter() {
    return $("#lbOpponentFilter").value || "all";
}

function isHideLosersEnabled() {
    return $("#lbHideLosers").checked;
}

function applyFilters(entries) {
    const opponentFilter = getOpponentFilter();
    const hideLosers = isHideLosersEnabled();
    return entries.filter((entry) => {
        if (opponentFilter !== "all" && entry.opponentLlmName !== opponentFilter) {
            return false;
        }
        if (hideLosers) {
            const humanFinalScore = entry.humanFinalScore ?? 0;
            const llmFinalScore = entry.llmFinalScore ?? 0;
            return humanFinalScore >= llmFinalScore;
        }
        return true;
    });
}

function renderEmptyState(body) {
    const tr = document.createElement("tr");
    const emptyCell = [
        "0",
        "👻",
        0,
        0,
        "👻",
        "👻",
        "👻",
    ];
    emptyCell.forEach((c, i) => {
        const td = document.createElement("td");
        td.textContent = String(c);
        if (i === 0) td.classList.add("rank-cell");
        tr.appendChild(td);
    });
    body.appendChild(tr);
}

function renderLeaderboard() {
    const body = $("#lbBody");
    const pageInfo = $("#lbPageInfo");
    const prevButton = $("#lbPrev");
    const nextButton = $("#lbNext");
    const filtered = applyFilters(cachedEntries);
    const pageSize = getPageSize();
    const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));

    currentPageIndex = Math.min(currentPageIndex, pageCount - 1);
    const startIndex = currentPageIndex * pageSize;
    const pageEntries = filtered.slice(startIndex, startIndex + pageSize);

    body.innerHTML = "";
    if (pageEntries.length === 0) {
        renderEmptyState(body);
        pageInfo.textContent = "";
        prevButton.disabled = true;
        nextButton.disabled = true;
        return;
    }

    for (const entry of pageEntries) body.appendChild(lbRow(entry));

    pageInfo.textContent = t("lbPageInfo", {
        page: currentPageIndex + 1,
        pages: pageCount,
        total: filtered.length,
    });

    prevButton.disabled = currentPageIndex === 0;
    nextButton.disabled = currentPageIndex >= pageCount - 1;
}

function syncOpponentFilterLabel() {
    const select = document.getElementById("lbOpponentFilter");
    const label = document.getElementById("lbOpponentText");
    if (!select || !label) return;

    const opt = select.options[select.selectedIndex];
    const text = opt ? (opt.textContent || "") : "";
    label.textContent = text;
    label.title = text;
}

function updateOpponentOptions() {
    const select = $("#lbOpponentFilter");
    const currentValue = select.value || "all";

    const opponentNames = Array.from(
        new Set(cachedEntries.map((entry) => entry.opponentLlmName).filter(Boolean))
    ).sort((a, b) => a.localeCompare(b));

    select.innerHTML = "";

    const allOption = document.createElement("option");
    allOption.value = "all";
    allOption.textContent = t("lbOpponentHeader");
    select.appendChild(allOption);

    for (const name of opponentNames) {
        const option = document.createElement("option");
        option.value = name;
        option.textContent = name;
        select.appendChild(option);
    }

    if (currentValue !== "all" && !opponentNames.includes(currentValue)) {
        select.value = "all";
    } else {
        select.value = currentValue;
    }

    syncOpponentFilterLabel();
}

function ensureLeaderboardListeners() {
    if (listenersAttached) return;
    listenersAttached = true;

    $("#lbOpponentFilter").addEventListener("change", () => {
        syncOpponentFilterLabel()
        currentPageIndex = 0;
        renderLeaderboard();
    });

    $("#lbHideLosers").addEventListener("change", () => {
        currentPageIndex = 0;
        renderLeaderboard();
    });

    $("#lbPrev").addEventListener("click", () => {
        currentPageIndex = Math.max(0, currentPageIndex - 1);
        renderLeaderboard();
    });

    $("#lbNext").addEventListener("click", () => {
        currentPageIndex += 1;
        renderLeaderboard();
    });

    window.addEventListener(
        "resize",
        () => {
            if (resizeRaf) cancelAnimationFrame(resizeRaf);
            resizeRaf = requestAnimationFrame(() => {
                resizeRaf = 0;
                onResize();
            });
        },
        { passive: true }
    );
}

function onResize() {
    const nextSize = getPageSize();
    if (nextSize === lastPageSize) return; // nothing to do
    lastPageSize = nextSize;
    renderLeaderboard();
}

export async function refreshLeaderboard() {
    const data = await httpGetJson(`/api/v1/leaderboard?limit=${PAGE_LIMIT}`);
    cachedEntries = data.entries || [];
    currentPageIndex = 0;
    ensureLeaderboardListeners();
    updateOpponentOptions();
    renderLeaderboard();
}
