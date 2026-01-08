import {$} from "./core/dom.js";
import {LANG, loadI18n} from "./core/i18n.js";
import {state, STORAGE_KEY_NICKNAME} from "./core/state.js";
import {initStaticText} from "./ui/staticText.js";
import {bindUi} from "./game/bindUi.js";
import {resetRoundUi} from "./game/roundUi.js";
import {setLobbyTab} from "./game/lobbyTabs.js";
import {checkLandingPopup, loadLicenseHtml} from "./ui/modals.js";
import {ensurePlayerSession, tryRecoverActiveSession} from "./features/session.js";
import {loadModels} from "./features/models.js";
import {showNetError} from "./ui/toast.js";
import {setNet} from "./ui/netIndicator.js";
import {installCompatGlobals} from "./compat/globals.js";

export async function main() {
    installCompatGlobals();

    await loadI18n(LANG);
    initStaticText();

    // Load saved nickname
    const savedNick = localStorage.getItem(STORAGE_KEY_NICKNAME);
    if (savedNick) {
        state.nickname = savedNick;
        const nl = $("#nicknameLight");
        const np = $("#nicknamePremium");
        if (nl) nl.value = savedNick;
        if (np) np.value = savedNick;
    }

    bindUi();
    setNet(false);
    resetRoundUi();

    setLobbyTab("LIGHTWEIGHT");
    await loadLicenseHtml();
    checkLandingPopup();

    try {
        await ensurePlayerSession();
        await loadModels();

        // Attempt to recover an active session (e.g., after F5 refresh)
        await tryRecoverActiveSession();
    } catch (e) {
        showNetError(e);
    }
}
