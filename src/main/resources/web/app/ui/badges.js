import {escapeHtml} from "../core/utils.js";
import {t} from "../core/i18n.js";

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

function resolveQuestionSetDescription(meta) {
    if (!meta) return "";

    // Prefer i18n key (expected to live in i18n/{lang}/models.js, like model descriptions)
    const key = meta.questionSetDescriptionI18nKey;
    if (key) {
        const localized = t(key);
        // t() returns the key itself if missing
        if (localized && localized !== key) {
            return String(localized || "").trim();
        }
    }

    // Fallback to static description
    return String(meta.questionSetDescription || "").trim();
}

/** shields.io static endpoint (avoids path escaping issues) */
export function shieldsStaticUrl({label, message, color, labelColor = LABEL_COLOR}) {
    const qs = new URLSearchParams({
        label: String(label ?? ""),
        message: String(message ?? ""),
        color: String(color ?? SET_COLOR),
        labelColor: String(labelColor ?? LABEL_COLOR),
        style: "flat-square",
        cacheSeconds: "86400",
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
    const setDescRaw = resolveQuestionSetDescription(meta);
    const setDesc = setDescRaw.replace(/\s+/g, " ").trim();
    const diffEnum = normDifficultyEnum(meta?.difficulty);
    const diffPretty = prettyDifficulty(diffEnum);

    const parts = [];

    if (setName) {
        const src = shieldsStaticUrl({
            label: "SET",
            message: setName,
            color: SET_COLOR,
        });

        const tooltip = setDesc ? `Set: ${setName} — ${setDesc}` : `Set: ${setName}`;
        const dataAttrs =
            `<img class="gh-badge" loading="lazy" src="${src}" ` +
            `data-kind="questionset" ` +
            `data-qs-name="${escapeHtml(setName)}" ` +
            `data-qs-desc="${escapeHtml(setDesc || "")}"`;

        parts.push(
            `${dataAttrs} ` +
            `alt="${escapeHtml(tooltip)}" title="${escapeHtml(tooltip)}">`
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
            `data-kind="difficulty" ` +
            `data-diff-name="${escapeHtml(diffPretty || diffEnum)}" ` +
            `alt="${escapeHtml(`Difficulty: ${diffPretty || diffEnum}`)}" title="${escapeHtml(`Difficulty: ${diffPretty || diffEnum}`)}">`
        );
    }

    if (!parts.length) return "";
    return `<span class="gh-badges">${parts.join("")}</span>`;
}