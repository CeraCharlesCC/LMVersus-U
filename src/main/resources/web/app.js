/* app.js - Vanilla JS frontend for LMVU (Ktor same-origin) */

const $ = (sel, root = document) => root.querySelector(sel);

(async () => {
    const {main} = await import("./app/main.js");
    await main();
})().catch((e) => {
    console.error(e);
    alert(e?.message || String(e));
});
