import {$} from "../core/dom.js";

export function setNet(ok) {
    const dot = $("#netDot");
    if (!dot) return;
    dot.classList.toggle("ok", !!ok);
    dot.classList.toggle("bad", !ok);
}
