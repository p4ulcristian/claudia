import { Marked } from "marked";
import DOMPurify from "dompurify";
import hljs from "highlight.js/lib/common";
import clojure from "highlight.js/lib/languages/clojure";
import dockerfile from "highlight.js/lib/languages/dockerfile";

hljs.registerLanguage("clojure", clojure);
hljs.registerAliases(["cljs", "cljc", "bb"], { languageName: "clojure" });
hljs.registerLanguage("dockerfile", dockerfile);

const marked = new Marked({
  gfm: true,
  breaks: false,
  renderer: {
    code({ text, lang }) {
      const language = lang && hljs.getLanguage(lang) ? lang : undefined;
      const html = language ? hljs.highlight(text, { language }).value : hljs.highlightAuto(text).value;
      return `<div class="code"><button class="copy" type="button" title="Copy">Copy</button><pre><code class="hljs">${html}</code></pre></div>`;
    },
    link({ href, text }) {
      return `<a href="${href}" target="_blank" rel="noreferrer">${text}</a>`;
    },
  },
});

export const md = (src: string) => DOMPurify.sanitize(marked.parse(src, { async: false }) as string, { ADD_ATTR: ["target"] });

export function highlight(code: string, lang?: string) {
  const language = lang && hljs.getLanguage(lang) ? lang : undefined;
  return language ? hljs.highlight(code, { language }).value : hljs.highlightAuto(code).value;
}

// Highlighted code split into lines, each line's markup whole: a span open across a line break
// is closed at its end and opened again on the next line.
export function highlightLines(code: string, lang?: string): string[] {
  const lines: string[] = [];
  const open: string[] = [];
  let cur = "";
  for (const [tok] of highlight(code, lang).matchAll(/<span[^>]*>|<\/span>|\n|[^<\n]+/g)) {
    if (tok === "\n") {
      lines.push(cur + "</span>".repeat(open.length));
      cur = open.join("");
    } else {
      cur += tok;
      if (tok.startsWith("<span")) open.push(tok);
      else if (tok === "</span>") open.pop();
    }
  }
  lines.push(cur);
  return lines;
}

// One listener for every code block's copy button.
document.addEventListener("click", (e) => {
  const btn = (e.target as HTMLElement).closest?.(".code .copy");
  if (!btn) return;
  const code = btn.parentElement?.querySelector("code")?.textContent ?? "";
  navigator.clipboard.writeText(code).then(() => {
    btn.textContent = "Copied";
    setTimeout(() => (btn.textContent = "Copy"), 1200);
  });
});
