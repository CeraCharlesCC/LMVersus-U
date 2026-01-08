import { escapeHtml } from "../core/utils.js";

/** GitHub-ish dark label background */
const LABEL_COLOR = "30363d";

/** Set badge message color (neutral gray) */
const SET_COLOR = "6e7681";

/** Difficulty label + colors */
const DIFF_LABEL = {
    EASY: "Easy",
    MEDIUM: "Medium",
    HARD: "Hard",
    VERY_HARD: "Very Hard",
    IMPOSSIBLE: "Impossible",
};

const DIFF_COLOR = {
    EASY: "2da44e",        // green
    MEDIUM: "bf8700",      // amber
    HARD: "d18616",        // orange
    VERY_HARD: "cf222e",   // red
    IMPOSSIBLE: "8250df",  // purple
};

function normDifficultyEnum(d) {
    return String(d || "").trim().toUpperCase();
}

function escapeRegExp(s) {
    return String(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** shields.io static endpoint (avoids path escaping issues) */
export function shieldsStaticUrl({ label, message, color, labelColor = LABEL_COLOR }) {
    const qs = new URLSearchParams({
        label: String(label ?? ""),
        message: String(message ?? ""),
        color: String(color ?? SET_COLOR),
        labelColor: String(labelColor ?? LABEL_COLOR),
        style: "flat",
    });
    return `https://img.shields.io/static/v1?${qs.toString()}`;
}

export function prettyDifficulty(difficultyEnum) {
    const k = normDifficultyEnum(difficultyEnum);
    return DIFF_LABEL[k] || (k ? k.replaceAll("_", " ") : "");
}

export function difficultyColor(difficultyEnum) {
    const k = normDifficultyEnum(difficultyEnum);
    return DIFF_COLOR[k] || "6e7681";
}

/** Returns "" if there are no badges */
export function renderModelBadgesHtml(meta) {
    const setName = String(meta?.questionSetDisplayName || "").trim();
    const diffEnum = normDifficultyEnum(meta?.difficulty);
    const diffPretty = prettyDifficulty(diffEnum);

    const parts = [];

    if (setName) {
        const src = shieldsStaticUrl({
            label: "SET",
            message: setName,
            color: SET_COLOR,
        });
        parts.push(
            `<img class="gh-badge" loading="lazy" src="${src}" ` +
            `alt="${escapeHtml(`Set: ${setName}`)}" title="${escapeHtml(`Set: ${setName}`)}">`
        );
    }

    if (diffEnum) {
        const src = shieldsStaticUrl({
            label: "DIFF",
            message: diffPretty || diffEnum.replaceAll("_", " "),
            color: difficultyColor(diffEnum),
        });
        parts.push(
            `<img class="gh-badge" loading="lazy" src="${src}" ` +
            `alt="${escapeHtml(`Difficulty: ${diffPretty || diffEnum}`)}" title="${escapeHtml(`Difficulty: ${diffPretty || diffEnum}`)}">`
        );
    }

    if (!parts.length) return "";
    return `<span class="gh-badges">${parts.join("")}</span>`;
}