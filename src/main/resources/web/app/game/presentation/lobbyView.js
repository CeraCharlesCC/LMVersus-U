import { $ } from "../../core/dom.js";
import { LANG } from "../../core/i18n.js";
import { renderModelSelectorWidget } from "../../features/models.js";
import { refreshLeaderboard } from "../../features/leaderboard.js";
import { showNetError, toast } from "../../ui/toast.js";
import { t } from "../../core/i18n.js";

export function createLobbyView({ actions }) {
    const ac = new AbortController();
    const signal = ac.signal;
    let lastMode = null;
    let lastModelsKey = "";
    let lastTab = null;

    const tabButtons = Array.from(document.querySelectorAll(".tab-btn"));
    tabButtons.forEach((btn) => {
        btn.addEventListener(
            "click",
            () => actions.selectLobbyTab(btn.dataset.tab),
            { signal }
        );
    });

    $("#btnStartLight")?.addEventListener(
        "click",
        () => {
            const nickname = $("#nicknameLight")?.value || "";
            const opponentSpecId = $("#opponentLightVal")?.value || "";
            actions.startMatch({
                mode: "LIGHTWEIGHT",
                nickname,
                opponentSpecId,
                locale: navigator.language || (LANG === "ja" ? "ja-JP" : "en"),
            });
        },
        { signal }
    );

    $("#btnStartPremium")?.addEventListener(
        "click",
        () => {
            const nickname = $("#nicknamePremium")?.value || "";
            const opponentSpecId = $("#opponentPremiumVal")?.value || "";
            actions.startMatch({
                mode: "PREMIUM",
                nickname,
                opponentSpecId,
                locale: navigator.language || (LANG === "ja" ? "ja-JP" : "en"),
            });
        },
        { signal }
    );

    $("#btnRefreshLb")?.addEventListener(
        "click",
        () => refreshLeaderboard().catch(showNetError),
        { signal }
    );

    function renderTabs(state) {
        const activeTab = state.ui.lobbyTab || state.mode;
        tabButtons.forEach((btn) => {
            const active = btn.dataset.tab === activeTab;
            btn.classList.toggle("is-active", active);
            btn.setAttribute("aria-selected", active ? "true" : "false");
        });
        document.querySelectorAll(".tab-panel").forEach((panel) => {
            panel.classList.toggle("is-active", panel.id === `panel-${activeTab}`);
        });
    }

    function renderOpponentSelectors(state) {
        const lightKey = (state.models.LIGHTWEIGHT || []).map((m) => m.id).join(",");
        const premiumKey = (state.models.PREMIUM || []).map((m) => m.id).join(",");
        const modelsKey = `${lightKey}::${premiumKey}`;
        if (modelsKey === lastModelsKey && state.ui.lobbyTab === lastMode) return;
        lastModelsKey = modelsKey;
        lastMode = state.ui.lobbyTab;

        ["LIGHTWEIGHT", "PREMIUM"].forEach((mode) => {
            const models = state.models[mode] || [];
            const container = $(mode === "LIGHTWEIGHT" ? "#opponentLight" : "#opponentPremium");
            const input = $(mode === "LIGHTWEIGHT" ? "#opponentLightVal" : "#opponentPremiumVal");
            if (!container || !input) return;
            renderModelSelectorWidget(container, input, models, mode);
        });
    }

    function maybeRefreshLeaderboard(state) {
        if (state.ui.lobbyTab !== "LEADERBOARD" || lastTab === "LEADERBOARD") {
            lastTab = state.ui.lobbyTab;
            return;
        }
        refreshLeaderboard().catch((e) => toast(t("toastError"), e.message));
        lastTab = state.ui.lobbyTab;
    }

    function renderNicknames(state) {
        const nick = state.player.nickname || "";
        const nl = $("#nicknameLight");
        const np = $("#nicknamePremium");
        if (nl && nl.value !== nick) nl.value = nick;
        if (np && np.value !== nick) np.value = nick;
    }

    return {
        render(state) {
            if (state.screen !== "LOBBY") return;
            renderTabs(state);
            renderNicknames(state);
            renderOpponentSelectors(state);
            maybeRefreshLeaderboard(state);
        },
        dispose() {
            ac.abort();
        },
    };
}
