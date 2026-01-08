import { $ } from "../core/dom.js";
import { t } from "../core/i18n.js";

export function initStaticText() {
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
