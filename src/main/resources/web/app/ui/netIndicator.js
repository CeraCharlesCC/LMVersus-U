import {$} from "../core/dom.js";

export function setNet(ok) {
    const dot = $("#netDot");
    dot.classList.toggle("ok", !!ok);
    dot.classList.toggle("bad", !ok);
}
