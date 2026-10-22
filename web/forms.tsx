// The predefined forms (src/forms.ts), drawn in the board's chalk style. Each gets its block's
// data as it stands after the draw calls (items merged by id).
import React, { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { FormName } from "../src/forms";
import { codeTokens, type Token } from "./shiki";

type Data = Record<string, any>;
const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const colorOf = (c?: string) => (c ? `var(--${c})` : undefined);

const STATUS_MARK: Record<string, string> = { done: "✓", running: "…", failed: "✗", skipped: "–", todo: "" };

// Stages side by side while each gets at least STAGE_MIN px; narrower, they stack top to bottom.
const STAGE_MIN = 120;

function Pipeline({ data }: { data: Data }) {
  const stages = list(data.stages);
  const box = useRef<HTMLDivElement>(null);
  const [down, setDown] = useState(false);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const fit = () => setDown(el.clientWidth < stages.length * STAGE_MIN);
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [stages.length]);
  return (
    <div className={`f-pipeline ${down ? "down" : ""}`} ref={box}>
      {stages.map((s, i) => (
        <React.Fragment key={s.id}>
          {i > 0 && <span className={`f-arrow ${stages[i - 1].status === "done" ? "lit" : ""}`} aria-hidden="true" />}
          <div className={`f-stage chalk-box st-${s.status ?? "todo"}`} data-item={s.id}>
            <span className="f-stage-mark">{STATUS_MARK[s.status ?? "todo"]}</span>
            <span className="f-stage-label">{s.label ?? s.id}</span>
            {s.note && <span className="f-note">{s.note}</span>}
          </div>
        </React.Fragment>
      ))}
    </div>
  );
}

function Timeline({ data }: { data: Data }) {
  return (
    <ol className="f-timeline">
      {list(data.events).map((e) => (
        <li key={e.id} className={`st-${e.status ?? "none"}`} data-item={e.id} style={{ "--dot": colorOf(e.color) } as React.CSSProperties}>
          <span className="f-at">{e.at}</span>
          <span className="f-dot" aria-hidden="true" />
          <span className="f-event">
            <b>{e.label ?? e.id}</b>
            {e.status && e.status !== "none" && <i className="f-tag">{e.status}</i>}
            {e.note && <span className="f-note">{e.note}</span>}
          </span>
        </li>
      ))}
    </ol>
  );
}

function Compare({ data }: { data: Data }) {
  return (
    <div className="f-compare">
      {list(data.options).map((o) => (
        <section key={o.id} className={`f-option chalk-box ${o.picked ? "picked" : ""}`} data-item={o.id}>
          <h4>{o.label ?? o.id}{o.picked && <span className="f-tag">picked</span>}</h4>
          {o.note && <p className="f-note">{o.note}</p>}
          <ul>
            {list(o.pros).map((p: string, i: number) => <li key={`p${i}`} className="pro">{p}</li>)}
            {list(o.cons).map((c: string, i: number) => <li key={`c${i}`} className="con">{c}</li>)}
          </ul>
        </section>
      ))}
    </div>
  );
}

function Stats({ data }: { data: Data }) {
  return (
    <div className="f-stats">
      {list(data.tiles).map((t) => (
        <div key={t.id} className="f-tile chalk-box" data-item={t.id} style={{ "--tile": colorOf(t.color) } as React.CSSProperties}>
          <span className="f-label">{t.label ?? t.id}</span>
          <span className="f-value">{t.value}</span>
          {typeof t.progress === "number" && (
            <span className="f-bar"><i style={{ width: `${Math.max(0, Math.min(1, t.progress)) * 100}%` }} /></span>
          )}
          {t.note && <span className="f-note">{t.note}</span>}
        </div>
      ))}
    </div>
  );
}

// Charts are Vega-Lite, loaded from the CDN the first time one is drawn, themed to the board.
const importUrl = new Function("u", "return import(u)") as (u: string) => Promise<any>;
let embedLib: Promise<any> | null = null;
const embed = () => (embedLib ??= importUrl("https://cdn.jsdelivr.net/npm/vega-embed@6/+esm").then((m) => m.default));

function Chart({ data }: { data: Data }) {
  const box = useRef<HTMLDivElement>(null);
  const key = JSON.stringify(data);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    let view: any;
    let gone = false;
    const cs = getComputedStyle(el);
    const v = (n: string) => cs.getPropertyValue(n).trim();
    const fg = v("--fg"), muted = v("--muted"), line = v("--line");
    const palette = ["--blue", "--amber", "--green", "--violet", "--red", "--grey"].map(v);
    const x = data.x ?? "x", y = data.y ?? "y";
    const values = list(data.rows).map(({ id: _id, ...rest }) => rest);
    const numericX = values.length > 0 && values.every((r) => typeof r[x] === "number");
    const spec = {
      $schema: "https://vega.github.io/schema/vega-lite/v5.json",
      width: "container",
      height: 220,
      background: "transparent",
      ...(data.title ? { title: data.title } : {}),
      data: { values },
      mark: { type: data.mark ?? "bar", tooltip: true, ...(data.mark === "line" || data.mark === "area" ? { point: true, strokeWidth: 3 } : { cornerRadiusEnd: 3 }) },
      encoding: {
        x: { field: x, type: numericX ? "quantitative" : "ordinal", sort: null, axis: { labelAngle: 0 } },
        y: { field: y, type: "quantitative" },
        ...(data.color ? { color: { field: data.color, type: "nominal" } } : { color: { value: palette[0] } }),
      },
      config: {
        font: "Atkinson Hyperlegible",
        view: { stroke: null },
        range: { category: palette },
        title: { font: "Kalam", fontSize: 18, fontWeight: 700, color: fg, anchor: "start" },
        axis: { labelColor: muted, titleColor: muted, domainColor: line, tickColor: line, gridColor: line, gridOpacity: 0.5, labelFontSize: 12, titleFont: "Kalam", titleFontSize: 14, titleFontWeight: 700 },
        legend: { labelColor: muted, titleColor: muted },
      },
    };
    embed()
      .then((vegaEmbed) => (gone ? null : vegaEmbed(el, spec, { actions: false, renderer: "svg" })))
      .then((r) => { view = r?.view; })
      .catch((e) => { if (!gone) el.textContent = `Chart failed: ${e.message ?? e}`; });
    return () => { gone = true; view?.finalize(); };
  }, [key]);
  return <div className="f-chart" ref={box} />;
}

// Code: real line numbers from data.line, coloured by Shiki (VS Code's grammars); diff: true reads a
// leading +/-/space per line (removed lines get no number); mark and notes point at those numbers.
// A long file folds down to the marked and noted lines with a few lines around each.
type CodeRow = { n: number | null; sign: "+" | "-" | ""; text: string };

const FOLD_OVER = 60; // longer code folds
const AROUND = 4; // lines kept open around each marked or noted line
const HEAD = 40; // lines shown when nothing is marked

function codeRows(data: Data): CodeRow[] {
  const first = Number.isFinite(data.line) ? Number(data.line) : 1;
  const raw = String(data.code ?? "").replace(/\n+$/, "").split("\n");
  let n = first;
  return raw.map((l) => {
    const sign = data.diff && (l[0] === "+" || l[0] === "-") ? (l[0] as "+" | "-") : "";
    const text = data.diff && (sign || l[0] === " ") ? l.slice(1) : l;
    return { n: sign === "-" ? null : n++, sign, text };
  });
}

function markedLines(mark: unknown): Set<number> {
  const out = new Set<number>();
  for (const m of list(mark)) {
    const [a, b] = String(m).split("-").map((x) => parseInt(x, 10));
    if (Number.isNaN(a)) continue;
    for (let i = a; i <= (Number.isNaN(b ?? NaN) ? a : b); i++) out.add(i);
  }
  return out;
}

// Which rows show: all of short code; of long code, the rows near marked or noted lines (or the head).
function shownRows(rows: CodeRow[], focus: Set<number>): boolean[] {
  if (rows.length <= FOLD_OVER) return rows.map(() => true);
  const idx = rows.flatMap((r, i) => (r.n != null && focus.has(r.n) ? [i] : []));
  if (!idx.length) return rows.map((_, i) => i < HEAD);
  const show = rows.map(() => false);
  for (const i of idx) for (let j = Math.max(0, i - AROUND); j <= Math.min(rows.length - 1, i + AROUND); j++) show[j] = true;
  return show;
}

const FONT_STYLE = (f?: number): React.CSSProperties | undefined =>
  f ? { fontStyle: f & 1 ? "italic" : undefined, fontWeight: f & 2 ? 700 : undefined, textDecoration: f & 4 ? "underline" : undefined } : undefined;

function Code({ data }: { data: Data }) {
  const rows = useMemo(() => codeRows(data), [data.code, data.line, data.diff]);
  const file = typeof data.file === "string" ? data.file : "";
  const ext = file.includes(".") ? file.split(".").pop() : undefined;
  const lang: string | undefined = data.lang ?? ext;
  const text = useMemo(() => rows.map((r) => r.text).join("\n"), [rows]);
  const [tokens, setTokens] = useState<Token[][] | null>(null);
  useEffect(() => {
    let gone = false;
    setTokens(null);
    codeTokens(text, lang).then((t) => { if (!gone) setTokens(t); }).catch(() => {});
    return () => { gone = true; };
  }, [text, lang]);
  const marked = markedLines(data.mark);
  const notes = new Map<number, any[]>();
  for (const nt of list(data.notes)) notes.set(Number(nt.line), [...(notes.get(Number(nt.line)) ?? []), nt]);
  const shown = data.whole ? rows.map(() => true) : shownRows(rows, new Set([...marked, ...notes.keys()]));
  const [opened, setOpened] = useState<Set<number>>(new Set()); // first row of each unfolded stretch
  const folded = shown.some((v) => !v);
  const [copied, setCopied] = useState(false);
  const copy = () => {
    navigator.clipboard.writeText(rows.filter((r) => r.sign !== "-").map((r) => r.text).join("\n")).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  };
  const cut = file.lastIndexOf("/") + 1;
  const firstNo = rows.find((r) => r.n != null)?.n;
  const lastNo = [...rows].reverse().find((r) => r.n != null)?.n;

  const out: React.ReactNode[] = [];
  for (let i = 0; i < rows.length; ) {
    if (!shown[i]) {
      let j = i;
      while (j < rows.length && !shown[j]) j++;
      if (!opened.has(i)) {
        const start = i;
        out.push(
          <button key={`fold${i}`} type="button" className="f-fold" onClick={() => setOpened((o) => new Set(o).add(start))}>
            ⋯ {j - i} line{j - i === 1 ? "" : "s"}
          </button>,
        );
        i = j;
        continue;
      }
    }
    const r = rows[i];
    out.push(
      <div key={i} className={`f-ln ${r.sign === "+" ? "add" : r.sign === "-" ? "del" : ""} ${r.n != null && marked.has(r.n) ? "hot" : ""}`}>
        <span className="num">{r.n ?? ""}</span>
        <span className="sign">{r.sign === "-" ? "−" : r.sign}</span>
        <code>
          {tokens?.[i]?.length ? tokens[i].map((t, k) => <span key={k} style={{ color: t.color, ...FONT_STYLE(t.fontStyle) }}>{t.content}</span>) : r.text || " "}
        </code>
      </div>,
    );
    if (r.n != null)
      for (const nt of notes.get(r.n) ?? [])
        out.push(
          <div key={`n${nt.id}`} className="f-pin" data-item={nt.id} style={{ "--pin": colorOf(nt.color) } as React.CSSProperties}>
            <span className="chalk-box">{nt.text}</span>
          </div>,
        );
    i++;
  }

  return (
    <div className={`f-code ${data.diff ? "is-diff" : ""}`}>
      <div className="f-code-head">
        {file ? (
          <span className="f-file" title={file}>
            <span className="dir">{file.slice(0, cut)}</span><b>{file.slice(cut)}</b>
            {firstNo != null && <span className="at">:{firstNo}{lastNo != null && lastNo !== firstNo ? `–${lastNo}` : ""}</span>}
          </span>
        ) : <span className="f-file" />}
        {lang && <span className="f-lang">{lang}</span>}
        {folded && (
          <button type="button" className="f-copy" onClick={() => setOpened((o) => (o.size ? new Set() : new Set(rows.map((_, i) => i))))}>
            {opened.size ? "fold" : "unfold"}
          </button>
        )}
        <button type="button" className="f-copy" onClick={copy}>{copied ? "copied" : "copy"}</button>
      </div>
      {data.error && <p className="f-note f-code-err">{data.error}</p>}
      <div className="f-lines">{out}</div>
    </div>
  );
}

// A chalk folder, open or shut.
function FolderIcon({ open }: { open: boolean }) {
  return (
    <svg className={`fo ${open ? "open" : ""}`} viewBox="0 0 22 16" aria-hidden="true">
      {open
        ? <path d="M2 4.2c0-.8.6-1.4 1.4-1.4h4.4l1.6 1.8h7c.8 0 1.4.6 1.4 1.4v1.2H6.2L3.4 13.6H3.2c-.7 0-1.2-.5-1.2-1.2z M6.2 7.2h13.6l-2.6 6.4H3.4z" />
        : <path d="M2 4.2c0-.8.6-1.4 1.4-1.4h4.4l1.6 1.8h8.4c.8 0 1.4.6 1.4 1.4v7.2c0 .8-.6 1.4-1.4 1.4H3.4c-.8 0-1.4-.6-1.4-1.4z" />}
    </svg>
  );
}

// A file as a chalk tag with its extension, coloured by kind.
const EXT_COLOR: Record<string, string> = {
  ts: "blue", tsx: "blue", mts: "blue", js: "yellow", jsx: "yellow", mjs: "yellow", cjs: "yellow",
  clj: "green", cljs: "green", cljc: "green", edn: "teal", bb: "green",
  md: "grey", txt: "grey", json: "amber", yaml: "amber", yml: "amber", toml: "amber", lock: "grey",
  css: "violet", scss: "violet", html: "orange", svg: "orange", sql: "cyan", sh: "lime", py: "sky",
  rs: "orange", go: "cyan", swift: "orange", service: "pink", png: "magenta", jpg: "magenta", webp: "magenta",
};
function ExtTag({ name }: { name: string }) {
  const dot = name.lastIndexOf(".");
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : "";
  const c = EXT_COLOR[ext];
  return (
    <span className="ft" style={{ "--ft": c ? (c === "grey" ? "var(--muted)" : `var(--c-${c}, var(--${c}))`) : "var(--muted)" } as React.CSSProperties}>
      {ext ? ext.slice(0, 5) : "·"}
    </span>
  );
}

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)$/i;

// An image over the whole window; a click or Escape closes it.
function ImageBig({ src, close }: { src: string; close: () => void }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopImmediatePropagation(); close(); } };
    window.addEventListener("keydown", key, true);
    return () => window.removeEventListener("keydown", key, true);
  }, [close]);
  return createPortal(
    <div className="img-big" onClick={close} role="dialog" aria-label="Image">
      <img src={src} alt="" />
    </div>,
    document.body,
  );
}

// Files: a folder as a tree, read live through the runner (src/files.ts); folders open on click, a
// file opens in place as a code block. Marks point files out; git status shows as a letter.
type Entry = { name: string; dir: boolean; status?: string; changed?: number; planned?: boolean; lines?: number };
const STATUS_TIP: Record<string, string> = { M: "changed", A: "added", U: "new, not in git yet", D: "deleted" };

function Files({ data, chat, block }: { data: Data; chat?: string; block?: string }) {
  const root: string = typeof data.root === "string" ? data.root : "";
  // sketch: files that don't exist (yet), given by Claude with their code; demo: the gallery's sample.
  // With root too, the sketch's files are planned additions to the real tree, each tagged "planned".
  type Made = { path: string; dir?: boolean; status?: string; code?: string };
  const sketch: Made[] | null = Array.isArray(data.sketch) ? data.sketch : null;
  const demo: Made[] | null = Array.isArray(data.demo) ? data.demo : null;
  const live = !!root && !demo;
  const made: Made[] = sketch ?? demo ?? [];
  const marks = new Map<string, any>(list(data.marks).map((m) => [String(m.path ?? "").replace(/^\.?\//, "").replace(/\/$/, ""), m]));
  const ancestors = (p: string) => p.split("/").slice(0, -1).map((_, i, a) => a.slice(0, i + 1).join("/"));
  const opened0 = typeof data.file === "string" ? data.file.replace(/^\.?\//, "") : null; // a file Claude opened for the user
  const [open, setOpen] = useState<Set<string>>(() => new Set(["", ...list(data.open).map(String), ...[...marks.keys()].flatMap(ancestors), ...(opened0 ? ancestors(opened0) : [])]));
  const [dirs, setDirs] = useState<Map<string, Entry[] | { error: string }>>(new Map());
  const [file, setFile] = useState<{ path: string; text?: string; error?: string; image?: string } | null>(null);
  const [tick, setTick] = useState(0);

  const url = (path: string, read = false) => `/api/chats/${chat}/files?root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}${read ? "&read=1" : ""}`;
  // Every open folder is read (again after "refresh").
  useEffect(() => {
    if (!live || !chat) return;
    let gone = false;
    for (const d of open)
      fetch(url(d)).then((r) => r.json()).then((j) => {
        if (!gone) setDirs((m) => new Map(m).set(d, j.entries ?? { error: j.error ?? "can't read" }));
      }).catch(() => {});
    return () => { gone = true; };
  }, [[...open].join("|"), root, chat, tick]);

  const entriesOf = (d: string): Entry[] | { error: string } | undefined => {
    // Folders come from the paths too ("src/a/b.clj" makes src and src/a), not only from dir entries.
    const pre = d ? `${d}/` : "";
    let real = live ? dirs.get(d) : [];
    if (real === undefined) return undefined;
    // A folder that is only planned isn't on disk: its planned files alone.
    if ("error" in real) {
      if (!made.some((e) => e.path.startsWith(pre))) return real;
      real = [];
    }
    const out = new Map<string, Entry>(real.map((e) => [e.name, e]));
    for (const e of made) {
      const p = e.path.replace(/\/$/, "");
      if (!p.startsWith(pre)) continue;
      const rest = p.slice(pre.length);
      const slash = rest.indexOf("/");
      const name = slash < 0 ? rest : rest.slice(0, slash);
      if (!name) continue;
      const isDir = slash >= 0 || !!e.dir;
      const onDisk = out.get(name);
      // Only what isn't on disk is planned (a sketch file over a real one keeps the real one).
      if (onDisk && live) continue;
      const lines = slash < 0 && typeof e.code === "string" ? e.code.replace(/\n$/, "").split("\n").length : undefined;
      out.set(name, { ...(onDisk ?? {}), name, dir: isDir, planned: !!sketch, ...(lines ? { lines } : {}), ...(slash < 0 && e.status ? { status: e.status } : {}) });
    }
    return [...out.values()].sort((a, b) => Number(b.dir) - Number(a.dir) || a.name.localeCompare(b.name));
  };
  const [big, setBig] = useState<string | null>(null); // an image shown over the window
  const openFile = (path: string) => {
    if (file?.path === path) return setFile(null);
    if (live && IMAGE_EXT.test(path)) return setFile({ path, image: url(path) + "&image=1" });
    setFile({ path });
    // A planned (sketched) file shows the code Claude gave; a file on disk is read.
    const m = made.find((e) => e.path.replace(/\/$/, "") === path);
    const cut = path.lastIndexOf("/");
    const listed = dirs.get(cut < 0 ? "" : path.slice(0, cut));
    const onDisk = live && Array.isArray(listed) && listed.some((x) => x.name === path.slice(cut + 1));
    if (m && !onDisk) return setFile({ path, text: m.code ?? (demo ? "// a file opens here, coloured like VS Code" : "(no code given for this file)") });
    if (!live) return setFile({ path, error: "not given" });
    fetch(url(path, true)).then((r) => r.json()).then((j) => setFile({ path, ...j })).catch(() => setFile({ path, error: "can't read it" }));
  };
  // Claude names a file (data.file): it opens, now and whenever it names another.
  useEffect(() => {
    if (!opened0) return;
    setOpen((o) => new Set([...o, ...ancestors(opened0)]));
    setFile(null);
    openFile(opened0);
  }, [opened0, root]);
  const toggle = (d: string) => setOpen((o) => { const n = new Set(o); n.has(d) ? n.delete(d) : n.add(d); return n; });

  const rows: React.ReactNode[] = [];
  const walk = (d: string, depth: number) => {
    const es = entriesOf(d);
    if (!es) { rows.push(<div key={`l${d}`} className="f-tree-row muted" style={{ "--depth": depth } as React.CSSProperties}>…</div>); return; }
    if ("error" in es) { rows.push(<div key={`e${d}`} className="f-tree-row err" style={{ "--depth": depth } as React.CSSProperties}>{es.error}</div>); return; }
    for (const e of es) {
      const path = d ? `${d}/${e.name}` : e.name;
      const m = marks.get(path);
      const isOpen = e.dir ? open.has(path) : file?.path === path;
      rows.push(
        <button
          key={path}
          type="button"
          className={`f-tree-row ${e.dir ? "dir" : "file"} ${isOpen ? "open" : ""} ${m ? "marked" : ""}`}
          style={{ "--depth": depth, "--mark": colorOf(m?.color) ?? "var(--amber)" } as React.CSSProperties}
          data-item={m?.id}
          onClick={() => (e.dir ? toggle(path) : openFile(path))}
        >
          {e.dir ? <FolderIcon open={isOpen} /> : <ExtTag name={e.name} />}
          <span className="nm">{e.name}</span>
          {!e.dir && e.lines !== undefined && <span className="f-lines-n">{e.lines}</span>}
          {e.planned && <span className="f-planned" title="Not on disk: planned, as Claude sketched it">planned</span>}
          {e.status && <span className={`st st-${e.status}`} title={STATUS_TIP[e.status]}>{e.status}</span>}
          {e.dir && e.changed ? <span className="st st-M" title={`${e.changed} changed inside`}>•</span> : null}
          {m?.note && <span className="mk">{m.note}</span>}
        </button>,
      );
      if (e.dir && isOpen) walk(path, depth + 1);
      if (!e.dir && isOpen && file)
        rows.push(
          <div key={`f${path}`} className="f-tree-file" style={{ "--depth": depth + 1 } as React.CSSProperties}>
            {file.image ? (
              <div className="f-image" onDoubleClick={() => setBig(file.image!)} title="Double-click to see it big">
                <img src={file.image} alt={path} />
              </div>
            ) : file.error ? <p className="f-note">{file.error}</p> : file.text === undefined ? <p className="f-note">…</p> : (
              <Code data={{ file: path, code: file.text, whole: true }} />
            )}
          </div>,
        );
    }
  };
  walk("", 0);

  const home = root.replace(/^\/home\/[^/]+/, "~");
  return (
    <div className="f-files">
      <div className="f-code-head">
        <span className="f-file" title={root}>
          {!live && sketch ? <b>{typeof data.title === "string" ? data.title : "planned files"}</b>
            : <><span className="dir">{demo ? "~/Work/apps/" : home.slice(0, home.lastIndexOf("/") + 1)}</span><b>{demo ? "shop" : home.slice(home.lastIndexOf("/") + 1)}</b></>}
        </span>
        {/* Real or made up, said up front. */}
        {live && <span className="f-kind real" title="Read live from disk">on disk</span>}
        {sketch && <span className="f-kind made" title="Files Claude sketched; they are not on disk">{live ? `+ ${sketch.length} planned` : "sketch · not on disk"}</span>}
        {demo && <span className="f-kind made">example</span>}
        {open.size > 1 && <button type="button" className="f-copy" onClick={() => { setOpen(new Set([""])); setFile(null); }}>collapse all</button>}
        {live && <button type="button" className="f-copy" onClick={() => { setDirs(new Map()); setTick((t) => t + 1); }}>refresh</button>}
      </div>
      <div className="f-tree">{live && !chat ? <p className="f-note">Open this chat to browse its files.</p> : rows}</div>
      {big && <ImageBig src={big} close={() => setBig(null)} />}
    </div>
  );
}

const RENDER: Record<FormName, (p: { data: Data; chat?: string; block?: string }) => React.ReactNode> = {
  pipeline: Pipeline,
  timeline: Timeline,
  compare: Compare,
  chart: Chart,
  stats: Stats,
  code: Code,
  files: Files,
};

// chat: the chat whose picture this is, for forms that read through the runner (files).
export function FormView({ form, data, chat, block }: { form: FormName; data: Data; chat?: string; block?: string }) {
  const R = RENDER[form];
  return <div className={`form-view form-${form}`}>{R ? <R data={data} chat={chat} block={block} /> : <p className="f-note">Unknown form "{form}".</p>}</div>;
}
