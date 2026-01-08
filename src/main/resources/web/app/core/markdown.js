const md = window.markdownit({
    html: false,
    linkify: true,
    breaks: true,
});

export function renderMarkdownMath(text, targetEl) {
    targetEl.innerHTML = md.render(text ?? "");
    // KaTeX auto-render
    try {
        window.renderMathInElement(targetEl, {
            delimiters: [
                {left: "$$", right: "$$", display: true},
                {left: "\\[", right: "\\]", display: true},
                {left: "$", right: "$", display: false},
                {left: "\\(", right: "\\)", display: false},
            ],
            throwOnError: false,
        });
    } catch {
        // ignore
    }
}

export function escapeMarkdownInline(s) {
    return String(s ?? "")
        .replaceAll("*", "\\*")
        .replaceAll("_", "\\_")
        .replaceAll("`", "\\`");
}
