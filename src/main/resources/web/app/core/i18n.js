function detectLang() {
    const raw = (navigator.language || "en").toLowerCase();
    if (raw.startsWith("ja")) return "ja";
    return "en";
}

export const LANG = detectLang();

let I18N_EN = null;
let I18N_CUR = null;

function formatTemplate(str, vars) {
    if (!vars) return String(str ?? "");
    return String(str ?? "").replace(/\{(\w+)\}/g, (_, k) =>
        Object.prototype.hasOwnProperty.call(vars, k) ? String(vars[k]) : `{${k}}`
    );
}

export function t(key, vars) {
    const raw =
        (I18N_CUR && I18N_CUR[key]) ??
        (I18N_EN && I18N_EN[key]) ??
        key;
    return formatTemplate(raw, vars);
}

export async function loadI18n(lang) {
    async function loadLangSet(code) {
        const [uiRes, modelsRes] = await Promise.allSettled([
            import(`../../i18n/${code}/ui.js`),
            import(`../../i18n/${code}/models.js`),
        ]);

        const ui =
            uiRes.status === "fulfilled"
                ? uiRes.value
                : (console.warn(`Missing ui i18n '${code}'`, uiRes.reason), { default: {} });
        const models =
            modelsRes.status === "fulfilled"
                ? modelsRes.value
                : (console.warn(`Missing models i18n '${code}'`, modelsRes.reason), { default: {} });

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
