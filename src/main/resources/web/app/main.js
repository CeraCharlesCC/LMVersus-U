import {LANG, loadI18n} from "./core/i18n.js";
import {STORAGE_KEY_LANDING, STORAGE_KEY_NICKNAME} from "./core/state.js";
import {initStaticText} from "./ui/staticText.js";
import {bindUi} from "./game/bindUi.js";
import {loadLicenseHtml} from "./ui/modals.js";
import {ensurePlayerSession, tryRecoverActiveSession} from "./features/session.js";
import {loadModels} from "./features/models.js";
import {showNetError} from "./ui/toast.js";
import {installCompatGlobals} from "./compat/globals.js";
import {actions} from "./game/actions.js";

export async function main() {
    installCompatGlobals();

    await loadI18n(LANG);
    initStaticText();

    const savedNick = localStorage.getItem(STORAGE_KEY_NICKNAME);
    const landingAcked = !!localStorage.getItem(STORAGE_KEY_LANDING);
    actions.initApp({nickname: savedNick, landingAcked});
    actions.selectLobbyTab("LIGHTWEIGHT");

    bindUi();
    await loadLicenseHtml();

    try {
        const session = await ensurePlayerSession();
        actions.playerSessionLoaded(session);

        const models = await loadModels();
        actions.modelsLoaded(models);

        // Attempt to recover an active session (e.g., after F5 refresh)
        const recovery = await tryRecoverActiveSession(
            {LIGHTWEIGHT: models.light, PREMIUM: models.premium},
            savedNick
        );
        if (recovery) {
            actions.recoverSession(recovery);
        }
    } catch (e) {
        showNetError(e);
    }
}
