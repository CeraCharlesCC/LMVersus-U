import { $ } from "../core/dom.js";
import { state, STORAGE_KEY_LANDING } from "../core/state.js";
import { t } from "../core/i18n.js";
import { safeLsSet } from "../core/utils.js";
import { refreshLeaderboard } from "../features/leaderboard.js";

import { showNetError, toast } from "../ui/toast.js";
import { hideLicenseModal, hideMatchEndModal, isMatchEndVisible, showLicenseModal } from "../ui/modals.js";
import { initPeekButtonFollower } from "../ui/peekButtonFollower.js";
import { closeWs } from "./ws.js";
import { showLobby } from "./uiScreens.js";
import { getLlmScrollEl, renderResultDetails, setTopMobileTab } from "./roundUi.js";
import { setLobbyTab } from "./lobbyTabs.js";
import { applyFreeAnswerMode, giveUp, goNext, startMatch, startRound, submitAnswer } from "./actions.js";
import { bindWorkspaceEvents, initWorkspaceText } from "./workspace.js";

export function bindUi() {
    document.querySelectorAll(".tab-btn").forEach((btn) => {
        btn.addEventListener("click", () => setLobbyTab(btn.dataset.tab));
    });

    $("#btnLandingDismiss").addEventListener("click", () => {
        $("#landingOverlay").classList.add("hidden");
        $("#landingOverlay").setAttribute("aria-hidden", "true");
        safeLsSet(STORAGE_KEY_LANDING, "true");
    });

    $("#btnStartLight").addEventListener("click", () => startMatch("LIGHTWEIGHT"));
    $("#btnStartPremium").addEventListener("click", () => startMatch("PREMIUM"));

    $("#btnRefreshLb").addEventListener("click", () => refreshLeaderboard().catch(showNetError));

    $("#btnGiveUp").addEventListener("click", giveUp);

    $("#btnEndLobby").addEventListener("click", () => {
        hideMatchEndModal();
        closeWs();
        showLobby();
        import("./roundUi.js").then((m) => m.resetRoundUi());
        state.ui.sessionEnded = false;
        state.sessionId = null;
    });
    $("#btnEndLb").addEventListener("click", () => {
        hideMatchEndModal();
        closeWs();
        showLobby();
        setLobbyTab("LEADERBOARD");
        import("./roundUi.js").then((m) => m.resetRoundUi());
        state.ui.sessionEnded = false;
        state.sessionId = null;
    });

    $("#btnEndClose")?.addEventListener("click", () => {
        hideMatchEndModal();
    });

    window.addEventListener("keydown", (e) => {
        if (e.key !== "Escape") return;
        if (!isMatchEndVisible()) return;
        hideMatchEndModal();
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
            hideMatchEndModal();
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
        llmScroll.addEventListener(
            "scroll",
            () => {
                if (state.ui.programmaticScroll) return;
                state.ui.reasoningPinnedToTop = llmScroll.scrollTop <= 2;
            },
            { passive: true }
        );
    }

    // Re-render result details when crossing the responsive breakpoint (e.g., rotation)
    let resizeRaf = 0;
    window.addEventListener(
        "resize",
        () => {
            if (!state.ui.lastResultDetails) return;
            if (resizeRaf) cancelAnimationFrame(resizeRaf);
            resizeRaf = requestAnimationFrame(() => {
                const { note, details } = state.ui.lastResultDetails || {};
                renderResultDetails(note, details);
            });
        },
        { passive: true }
    );

    // keep behavior: if something calls old stubs, don’t crash
    window.selectOpponent = function () {
    };
    window.updateOpponentHint = function () {
    };

    // small safety: keep same error toast usage patterns if needed
    window.__lmvuToastError = (msg) => toast(t("toastError"), msg, "error");

    // Initialize workspace features (answer summary, copy tools, scratchpad, etc.)
    initWorkspaceText();
    bindWorkspaceEvents(submitAnswer);

    // Initialize peek button follower for scroll-following behavior
    initPeekButtonFollower();
}
