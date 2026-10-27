// The editor form (src/forms.ts, src/editor.ts): code Claude and the user both edit, in CodeMirror.
// The user's typing is saved into the picture as they type; Send hands their edits to Claude as a
// message, which the chat shows as a chip per file that opens its diff.
import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import { Annotation, Compartment, EditorState, type Extension } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection } from "@codemirror/view";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { bracketMatching, indentOnInput, syntaxHighlighting, HighlightStyle, StreamLanguage } from "@codemirror/language";
import { closeBrackets, closeBracketsKeymap } from "@codemirror/autocomplete";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { tags as t } from "@lezer/highlight";
import { javascript } from "@codemirror/lang-javascript";
import { python } from "@codemirror/lang-python";
import { sql } from "@codemirror/lang-sql";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { json } from "@codemirror/lang-json";
import { rust } from "@codemirror/lang-rust";
import { go } from "@codemirror/lang-go";
import { java } from "@codemirror/lang-java";
import { cpp } from "@codemirror/lang-cpp";
import { markdown } from "@codemirror/lang-markdown";
import { yaml } from "@codemirror/lang-yaml";
import { clojure } from "@codemirror/legacy-modes/mode/clojure";
import { shell } from "@codemirror/legacy-modes/mode/shell";
import { toml } from "@codemirror/legacy-modes/mode/toml";
import { ruby } from "@codemirror/legacy-modes/mode/ruby";
import { swift } from "@codemirror/legacy-modes/mode/swift";
import { lua } from "@codemirror/legacy-modes/mode/lua";
import { parsePatch } from "diff";
import { PatchDiff } from "./changes";

// The page's socket, for forms that write (the editor's typing and Send). Given only where the
// picture is live and the chat is the user's own.
export const PictureSend = createContext<((m: unknown) => void) | null>(null);

type File = { id: string; name?: string; lang?: string; code?: string; v?: number; edit?: string; rev?: number };

// A language by name or file extension; plain text when unknown.
const LANGS: Record<string, () => Extension> = {
  js: () => javascript(), mjs: () => javascript(), cjs: () => javascript(), jsx: () => javascript({ jsx: true }),
  ts: () => javascript({ typescript: true }), mts: () => javascript({ typescript: true }), tsx: () => javascript({ typescript: true, jsx: true }),
  javascript: () => javascript(), typescript: () => javascript({ typescript: true }),
  py: () => python(), python: () => python(),
  sql: () => sql(), html: () => html(), css: () => css(), json: () => json(),
  rs: () => rust(), rust: () => rust(), go: () => go(), java: () => java(),
  c: () => cpp(), h: () => cpp(), cpp: () => cpp(), cc: () => cpp(), hpp: () => cpp(),
  md: () => markdown(), markdown: () => markdown(), yaml: () => yaml(), yml: () => yaml(),
  clj: () => StreamLanguage.define(clojure), cljs: () => StreamLanguage.define(clojure), cljc: () => StreamLanguage.define(clojure),
  edn: () => StreamLanguage.define(clojure), bb: () => StreamLanguage.define(clojure), clojure: () => StreamLanguage.define(clojure),
  sh: () => StreamLanguage.define(shell), bash: () => StreamLanguage.define(shell), zsh: () => StreamLanguage.define(shell), shell: () => StreamLanguage.define(shell),
  toml: () => StreamLanguage.define(toml), rb: () => StreamLanguage.define(ruby), ruby: () => StreamLanguage.define(ruby),
  swift: () => StreamLanguage.define(swift), lua: () => StreamLanguage.define(lua),
};
const langKey = (f: File) => (f.lang ?? f.name?.split(".").pop() ?? "").toLowerCase();
const langOf = (f: File): Extension => LANGS[langKey(f)]?.() ?? [];

// VS Code's Dark+ colours, as in the files form (Shiki's dark-plus).
const DARK_PLUS = HighlightStyle.define([
  { tag: [t.keyword, t.bool, t.null, t.atom, t.tagName, t.self], color: "#569cd6" },
  { tag: [t.controlKeyword, t.moduleKeyword], color: "#c586c0" },
  { tag: [t.string, t.special(t.string)], color: "#ce9178" },
  { tag: t.regexp, color: "#d16969" },
  { tag: [t.number, t.integer, t.float], color: "#b5cea8" },
  { tag: [t.comment, t.lineComment, t.blockComment], color: "#6a9955" },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.macroName], color: "#dcdcaa" },
  { tag: [t.variableName, t.propertyName, t.attributeName, t.labelName], color: "#9cdcfe" },
  { tag: [t.typeName, t.className, t.namespace], color: "#4ec9b0" },
  { tag: [t.meta, t.processingInstruction], color: "#9b9b9b" },
  { tag: t.heading, color: "#569cd6", fontWeight: "bold" },
  { tag: t.emphasis, fontStyle: "italic" },
  { tag: t.strong, fontWeight: "bold" },
  { tag: t.link, color: "#4ec9b0", textDecoration: "underline" },
  { tag: t.invalid, color: "#f44747" },
]);

const THEME = EditorView.theme({
  "&": { color: "#d4d4d4", backgroundColor: "transparent", fontSize: "12.5px" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": { fontFamily: "var(--mono)", lineHeight: "1.6", overflow: "visible" },
  ".cm-content": { caretColor: "var(--accent)", padding: "6px 0" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--accent)", borderLeftWidth: "2px" },
  ".cm-gutters": { backgroundColor: "transparent", border: "none", color: "color-mix(in srgb, var(--muted) 60%, transparent)" },
  ".cm-lineNumbers .cm-gutterElement": { padding: "0 8px 0 12px" },
  ".cm-activeLine": { backgroundColor: "color-mix(in srgb, var(--line) 22%, transparent)" },
  ".cm-activeLineGutter": { backgroundColor: "transparent", color: "var(--fg)" },
  "&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection": { backgroundColor: "color-mix(in srgb, var(--blue) 30%, transparent)" },
  ".cm-matchingBracket": { backgroundColor: "color-mix(in srgb, var(--accent) 22%, transparent)", outline: "none" },
  ".cm-selectionMatch": { backgroundColor: "color-mix(in srgb, var(--amber) 16%, transparent)" },
  ".cm-panels": { backgroundColor: "var(--panel)", color: "var(--fg)" },
}, { dark: true });

// A change that came from the picture (Claude's code, another page's typing), not from this page's keys.
const Remote = Annotation.define<boolean>();
// How long the typing rests before it is saved.
const SAVE_MS = 300;

const list = (v: unknown): File[] => (Array.isArray(v) ? v.filter((f) => f && typeof f.id === "string") : []);
const shown = (f: File) => f.edit ?? f.code ?? "";

export function EditorForm({ data, chat, block, live }: { data: Record<string, any>; chat?: string; block?: string; live?: boolean }) {
  const send = useContext(PictureSend);
  const editable = !!(live && send && chat && block);
  const files = list(data.files);
  const [open, setOpen] = useState<string | undefined>(typeof data.open === "string" ? data.open : undefined);
  const openId = files.some((f) => f.id === open) ? open! : files[0]?.id;
  const [note, setNote] = useState("");
  const [, setTick] = useState(0);
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  // Per file: its editor state (undo history kept across tabs), what the picture last had for it
  // (Claude's version v and the text), its language and a pending save.
  const states = useRef(new Map<string, EditorState>());
  const synced = useRef(new Map<string, { v: number; text: string; lang: string }>());
  const langs = useRef(new Map<string, Compartment>());
  const access = useRef(new Compartment()).current;
  const accessOf = (on: boolean) => [EditorState.readOnly.of(!on), EditorView.editable.of(on)];
  const timers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const live$ = useRef({ editable, openId, send, chat, block });
  live$.current = { editable, openId, send, chat, block };
  const sendRef = useRef<() => void>(() => {});

  useEffect(() => { if (typeof data.open === "string") setOpen(data.open); }, [data.open]);

  const docOf = (id: string) => (id === live$.current.openId && view.current ? view.current.state : states.current.get(id))?.doc.toString() ?? "";

  const save = (id: string) => {
    const t = timers.current.get(id);
    if (t) clearTimeout(t);
    timers.current.delete(id);
    const s = synced.current.get(id);
    const { send, chat, block } = live$.current;
    if (!s || !send) return;
    const text = docOf(id);
    if (text === s.text) return;
    s.text = text;
    send({ t: "code_edit", chat, block, file: id, text, base: s.v });
  };

  const make = (f: File) => {
    const lang = new Compartment();
    langs.current.set(f.id, lang);
    return EditorState.create({
      doc: shown(f),
      extensions: [
        lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(), drawSelection(), history(),
        indentOnInput(), bracketMatching(), closeBrackets(), highlightSelectionMatches(),
        syntaxHighlighting(DARK_PLUS), lang.of(langOf(f)), THEME, EditorView.lineWrapping,
        access.of(accessOf(editable)),
        keymap.of([{ key: "Mod-Enter", run: () => { sendRef.current(); return true; } }, ...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, indentWithTab]),
        EditorView.updateListener.of((u) => {
          if (!u.docChanged || u.transactions.every((tr) => tr.annotation(Remote))) return;
          const id = f.id;
          const old = timers.current.get(id);
          if (old) clearTimeout(old);
          timers.current.set(id, setTimeout(() => save(id), SAVE_MS));
          setTick((n) => n + 1);
        }),
      ],
    });
  };

  // The picture changed: bring each file's editor up to it, without losing typing not saved yet.
  const filesKey = JSON.stringify(files.map((f) => [f.id, f.v, f.edit ?? null, f.code ?? null, langKey(f)]));
  useEffect(() => {
    const ids = new Set(files.map((f) => f.id));
    for (const id of [...states.current.keys()]) if (!ids.has(id)) { states.current.delete(id); synced.current.delete(id); langs.current.delete(id); }
    for (const f of files) {
      const text = shown(f), v = f.v ?? 0, lang = langKey(f);
      const s = synced.current.get(f.id);
      if (!s) {
        states.current.set(f.id, make(f));
        synced.current.set(f.id, { v, text, lang });
        continue;
      }
      const local = docOf(f.id);
      // Claude's new code replaces the text (Ctrl+Z brings back what was typed); another page's
      // typing only when nothing typed here waits to be saved.
      const replace = s.v !== v ? true : text !== s.text && local === s.text;
      if (s.v !== v) { const t = timers.current.get(f.id); if (t) clearTimeout(t); timers.current.delete(f.id); }
      const effects = lang !== s.lang ? [langs.current.get(f.id)!.reconfigure(langOf(f))] : [];
      if ((replace && local !== text) || effects.length) {
        const spec = { ...(replace && local !== text ? { changes: { from: 0, to: local.length, insert: text } } : {}), effects, annotations: Remote.of(true) };
        if (f.id === openId && view.current) view.current.dispatch(spec);
        else states.current.set(f.id, states.current.get(f.id)!.update(spec).state);
      }
      synced.current.set(f.id, { v, text: replace || local === text ? text : s.text, lang });
    }
    setTick((n) => n + 1);
  }, [filesKey]);

  // Editable only on the live picture of the user's own chat (an older picture, someone else's chat: read only).
  useEffect(() => {
    const spec = { effects: access.reconfigure(accessOf(editable)) };
    for (const [id, st] of states.current) if (!(id === openId && view.current)) states.current.set(id, st.update(spec).state);
    view.current?.dispatch(spec);
  }, [editable]);

  // One view; a tab switch swaps in that file's state.
  useEffect(() => {
    if (!host.current || !openId) return;
    const state = states.current.get(openId);
    if (!state) return;
    if (!view.current) {
      view.current = new EditorView({ state, parent: host.current, dispatchTransactions: (trs, v) => { v.update(trs); states.current.set(live$.current.openId!, v.state); } });
    } else if (view.current.state !== state) view.current.setState(state);
  }, [openId, filesKey]);
  useEffect(() => () => { for (const id of [...timers.current.keys()]) save(id); view.current?.destroy(); view.current = null; }, []);

  const changed = new Set(files.filter((f) => states.current.has(f.id) && docOf(f.id) !== (f.code ?? "")).map((f) => f.id));
  const doSend = () => {
    if (!editable || !changed.size) return;
    for (const f of files) save(f.id);
    send!({ t: "code_send", chat, block, note });
    setNote("");
  };
  sendRef.current = doSend;

  const pick = (id: string) => {
    if (view.current && openId) states.current.set(openId, view.current.state);
    setOpen(id);
  };

  if (!files.length) return <p className="f-note">No files in this editor yet.</p>;
  return (
    <div className="f-editor">
      {typeof data.note === "string" && data.note && <p className="ed-note">{data.note}</p>}
      <div className="f-code">
        <div className="f-code-head ed-head">
          <div className="ed-tabs" role="tablist">
            {files.map((f) => (
              <button type="button" role="tab" key={f.id} aria-selected={f.id === openId} className={f.id === openId ? "on" : ""} onClick={() => pick(f.id)} title={changed.has(f.id) ? "Changed: not sent yet" : undefined}>
                {f.name ?? f.id}{changed.has(f.id) && <span className="ed-dot" aria-label="changed" />}
              </button>
            ))}
          </div>
          {!editable && <span className="f-lang">{live ? "open this chat to edit" : "read only"}</span>}
        </div>
        <div className="ed-host" ref={host} />
      </div>
      {editable && (
        <div className="ed-send">
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); doSend(); } }}
            placeholder={changed.size ? "A note for Claude (optional)" : "Edit the code, then send it to Claude"}
          />
          <button type="button" className="primary" disabled={!changed.size} onClick={doSend} title="Send your edits to Claude (Ctrl+Enter in the code)">
            Send{changed.size > 1 ? ` ${changed.size} files` : ""}
          </button>
        </div>
      )}
    </div>
  );
}

// A message with the user's sent code: their note, and each file's diff and full code.
export type CodeSend = { name: string; added: number; removed: number; diff: string };
const SENT = /<claudia-code ([^>]*)>\n<diff>\n([\s\S]*?)\n<\/diff>\n<code>\n[\s\S]*?\n<\/code>\n<\/claudia-code>/g;
const attrOf = (attrs: string, k: string) => (attrs.match(new RegExp(`\\b${k}="([^"]*)"`))?.[1] ?? "").replace(/&quot;/g, '"').replace(/&amp;/g, "&");
export function codeSends(text: string): { note: string; sends: CodeSend[] } | null {
  if (!text.includes("<claudia-code ")) return null;
  const sends: CodeSend[] = [];
  const note = text.replace(SENT, (_, attrs: string, diff: string) => {
    sends.push({ name: attrOf(attrs, "name"), added: Number(attrOf(attrs, "added")) || 0, removed: Number(attrOf(attrs, "removed")) || 0, diff });
    return "";
  }).trim();
  return sends.length ? { note, sends } : null;
}

// One sent file in the chat: a chip that opens its diff.
export function CodeChip({ send }: { send: CodeSend }) {
  const [open, setOpen] = useState(false);
  const hunks = useMemo(() => (open ? parsePatch(send.diff)[0]?.hunks ?? [] : []), [open, send.diff]);
  return (
    <div className="code-sent">
      <button type="button" className={`code-chip ${open ? "on" : ""}`} onClick={() => setOpen(!open)} title={open ? "Hide the diff" : "Show what you changed"}>
        <span className="code-chip-name">Sent {send.name}</span>
        <span className="add">+{send.added}</span>
        <span className="del">−{send.removed}</span>
      </button>
      {open && <PatchDiff hunks={hunks} />}
    </div>
  );
}
