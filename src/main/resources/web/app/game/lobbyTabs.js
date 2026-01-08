import {state} from "../core/state.js";
import {t} from "../core/i18n.js";
import {toast} from "../ui/toast.js";
import {populateOpponentSelects} from "../features/models.js";
import {refreshLeaderboard} from "../features/leaderboard.js";

export function setLobbyTab(tab) {
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
