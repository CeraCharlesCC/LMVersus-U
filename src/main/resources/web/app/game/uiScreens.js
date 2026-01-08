import {$} from "../core/dom.js";
import {hideMatchEndModal} from "../ui/modals.js";

export function setGiveUpVisible(visible) {
    $("#btnGiveUp")?.classList.toggle("hidden", !visible);
}

export function showLobby() {
    hideMatchEndModal();
    $("#lobbyScreen").classList.add("screen-active");
    $("#gameScreen").classList.remove("screen-active");
    setGiveUpVisible(false);
}

export function showGame() {
    hideMatchEndModal();
    $("#lobbyScreen").classList.remove("screen-active");
    $("#gameScreen").classList.add("screen-active");
    setGiveUpVisible(true);
}
