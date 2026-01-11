import { $ } from "../core/dom.js";
import { STORAGE_KEY_LANDING } from "../core/state.js";
import { actions } from "./actions.js";
import { subscribe, getState } from "./store.js";
import { createLobbyView } from "./presentation/lobbyView.js";
import { createRoundView } from "./presentation/roundView.js";
import { createWorkspaceView } from "./workspace.js";
import { renderLandingOverlay, renderMatchEndModal, showLicenseModal, hideLicenseModal } from "../ui/modals.js";
import { setNet } from "../ui/netIndicator.js";
import { initPeekButtonFollower } from "../ui/peekButtonFollower.js";
import { GamePhase } from "./domain/gameState.js";
import { t } from "../core/i18n.js";
import { isMobileLayout } from "../core/utils.js";

export function bindUi() {
    const ac = new AbortController();
    const signal = ac.signal;

    const lobbyView = createLobbyView({ actions });
    const roundView = createRoundView({ actions });
    const workspaceView = createWorkspaceView({ actions });

    $("#btnGiveUp")?.addEventListener(
        "click",
        () => {
            if (!confirm(t("giveUpConfirm"))) return;
            actions.giveUp();
        },
        { signal }
    );

    $("#btnStartRound")?.addEventListener("click", actions.startRound, { signal });
    $("#btnSubmit")?.addEventListener("click", actions.submitAnswer, { signal });
    $("#btnNext")?.addEventListener("click", actions.next, { signal });

    $("#btnPeek")?.addEventListener("click", actions.peekReasoning, { signal });
    $("#btnTopQuestion")?.addEventListener("click", () => actions.setTopTab("question"), { signal });
    $("#btnTopReasoning")?.addEventListener("click", () => actions.setTopTab("reasoning"), { signal });

    $("#segText")?.addEventListener("click", () => actions.setFreeAnswerMode("text"), { signal });
    $("#segInt")?.addEventListener("click", () => actions.setFreeAnswerMode("int"), { signal });

    $("#freeText")?.addEventListener(
        "keydown",
        (event) => {
            if ((event.ctrlKey || event.metaKey) && event.key === "Enter") actions.submitAnswer();
        },
        { signal }
    );

    $("#intValue")?.addEventListener(
        "keydown",
        (event) => {
            if (event.key === "Enter") actions.submitAnswer();
        },
        { signal }
    );

    $("#btnLandingDismiss")?.addEventListener(
        "click",
        () => {
            localStorage.setItem(STORAGE_KEY_LANDING, "true");
            actions.ackLanding();
        },
        { signal }
    );

    $("#btnEndLobby")?.addEventListener(
        "click",
        () => {
            actions.next();
        },
        { signal }
    );

    $("#btnEndLb")?.addEventListener(
        "click",
        () => {
            actions.next();
            actions.selectLobbyTab("LEADERBOARD");
        },
        { signal }
    );

    $("#btnEndClose")?.addEventListener("click", actions.dismissMatchEnd, { signal });

    $("#matchEndOverlay")?.addEventListener(
        "click",
        (event) => {
            if (event.target && event.target.id === "matchEndOverlay") {
                actions.dismissMatchEnd();
            }
        },
        { signal }
    );

    window.addEventListener(
        "keydown",
        (event) => {
            if (event.key !== "Escape") return;
            const overlay = $("#matchEndOverlay");
            const isOpen = overlay && !overlay.classList.contains("hidden");
            if (isOpen) actions.dismissMatchEnd();
        },
        { signal }
    );

    $("#btnLicense")?.addEventListener("click", showLicenseModal, { signal });
    $("#btnCloseLicense")?.addEventListener("click", hideLicenseModal, { signal });
    $("#btnLicenseOk")?.addEventListener("click", hideLicenseModal, { signal });

    $("#licenseOverlay")?.addEventListener(
        "click",
        (event) => {
            if (event.target && event.target.id === "licenseOverlay") hideLicenseModal();
        },
        { signal }
    );

    window.addEventListener(
        "keydown",
        (event) => {
            const overlay = $("#licenseOverlay");
            const open = overlay && !overlay.classList.contains("hidden");
            if (open && event.key === "Escape") hideLicenseModal();
        },
        { signal }
    );

    initPeekButtonFollower(signal);

    const unsubscribe = subscribe((state) => {
        if (state.screen === "GAME") {
            $("#lobbyScreen")?.classList.remove("screen-active");
            $("#gameScreen")?.classList.add("screen-active");
            $("#btnGiveUp")?.classList.remove("hidden");
        } else {
            $("#lobbyScreen")?.classList.add("screen-active");
            $("#gameScreen")?.classList.remove("screen-active");
            $("#btnGiveUp")?.classList.add("hidden");
        }

        setNet(state.connection.netOk);
        renderLandingOverlay(state);
        renderMatchEndModal(state);

        lobbyView.render(state);
        roundView.render(state);
        workspaceView.render(state);

        if (state.phase === GamePhase.SESSION_RESOLVED && state.screen === "GAME") {
            $("#btnStartRound")?.setAttribute("disabled", "true");
        }
    });

    const initial = getState();
    renderLandingOverlay(initial);
    renderMatchEndModal(initial);
    lobbyView.render(initial);
    roundView.render(initial);
    workspaceView.render(initial);
    if (!isMobileLayout() && !initial.ui.workspace.scratchOpen) {
        actions.toggleScratchpad();
    }

    return () => {
        unsubscribe();
        lobbyView.dispose();
        roundView.dispose();
        workspaceView.dispose();
        ac.abort();
    };
}
