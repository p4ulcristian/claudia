// Shiki: VS Code's own grammars and its Dark+ theme, for the code form. Loaded from the CDN the
// first time code is drawn (like the charts' Vega), each language when first needed.

export type Token = { content: string; color?: string; fontStyle?: number }; // fontStyle bits: 1 italic, 2 bold, 4 underline

const THEME = "dark-plus";
// Names Shiki doesn't know but files use.
const ALIAS: Record<string, string> = { cljs: "clojure", cljc: "clojure", edn: "clojure", bb: "clojure", mts: "ts", cts: "ts", h: "c" };

const importUrl = new Function("u", "return import(u)") as (u: string) => Promise<any>;
let lib: Promise<{ hl: any; known: Record<string, unknown> }> | null = null;
const load = () =>
  (lib ??= importUrl("https://cdn.jsdelivr.net/npm/shiki@3/+esm").then(async (m) => ({
    hl: await m.createHighlighter({ themes: [THEME], langs: [] }),
    known: m.bundledLanguages,
  })));

// Lines of coloured tokens; null when the language is unknown (the form then shows plain text).
export async function codeTokens(code: string, lang?: string): Promise<Token[][] | null> {
  const want = lang?.toLowerCase();
  if (!want) return null;
  const { hl, known } = await load();
  const name = ALIAS[want] ?? want;
  if (!(name in known)) return null;
  if (!hl.getLoadedLanguages().includes(name)) await hl.loadLanguage(name);
  return hl.codeToTokensBase(code, { lang: name, theme: THEME });
}
