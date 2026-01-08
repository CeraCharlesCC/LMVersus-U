import {$} from "../core/dom.js";
import {httpGetJson} from "../core/net.js";
import {fmtPoints} from "../core/utils.js";

function lbRow(entry) {
    const tr = document.createElement("tr");
    const cells = [
        entry.rank ?? "",
        entry.nickname ?? "",
        fmtPoints(entry.bestScore ?? 0),
        entry.questionSetDisplayName ?? "",
        entry.opponentLlmName ?? "",
        entry.gameMode ?? "",
    ];
    for (const c of cells) {
        const td = document.createElement("td");
        td.textContent = String(c);
        tr.appendChild(td);
    }
    return tr;
}

export async function refreshLeaderboard() {
    const data = await httpGetJson("/api/v1/leaderboard?limit=10");
    const body = $("#lbBody");
    body.innerHTML = "";
    for (const e of data.entries || []) body.appendChild(lbRow(e));
}
