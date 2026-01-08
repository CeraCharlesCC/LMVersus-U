export function installCompatGlobals() {
    // these were in global scope before; keep them so inline handlers or old code won’t break
    window.selectOpponent = window.selectOpponent || function () {};
    window.updateOpponentHint = window.updateOpponentHint || function () {};
}
