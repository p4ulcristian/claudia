// The calls form (src/forms.ts): a Clojure project's function calls around one var, in three tabs.
// Unfold: the var's code, each call to a project var a link that opens that var's code nested under
// the calling line. Neighbours: the var in the middle, callers left (with the calling line), callees
// right (with their doc line). Map: columns by steps away, boxes coloured by folder, arrows for calls.
// Read live through the runner (src/calls.ts, the clojure form's clj-kondo index); the project's
// fingerprint is asked again every few seconds and what is shown is read again when it changes.
import React, { createContext, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { codeTokens, type Token } from "./shiki";

type Data = Record<string, any>;
type At = { row: number; col: number; endCol: number };
type Brief = { id: string; kind: "var" | "sql"; ns?: string; name: string; file: string; folder: string; row: number; private?: boolean };
type Callee = Brief & { doc?: string; at: At[] };
type Caller = Brief & { lines: { row: number; text: string }[] };
type Detail = Brief & { endRow: number; lang?: string; varKind?: string; args: string[]; doc?: string; code: string; callees: Callee[]; callers: Caller[] };
type Got = Detail | { error: string };
type MapNode = Brief & { dist: number };
type MapData = { start: string; nodes: MapNode[]; edges: [string, string][]; cut: Record<string, number> };
type Mark = { id: string; var: string; line?: number; note?: string; color?: string };
type View = "unfold" | "neighbours" | "map";

const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const POLL_MS = 4000;
// Longer than this, a var's code shows only its first lines and the lines with calls (± 1).
const LONG = 40;
const COLORS = ["var(--c-sky)", "var(--c-amber)", "var(--c-teal)", "var(--c-pink)", "var(--c-lime)", "var(--c-violet)", "var(--c-orange)", "var(--c-cyan)", "var(--c-red)", "var(--c-indigo)", "var(--c-yellow)", "var(--c-green)"];
const MARK: Record<string, string> = { red: "var(--red)", green: "var(--green)", amber: "var(--amber)", blue: "var(--blue)", violet: "var(--violet)", grey: "var(--muted)" };
const markColor = (m: Mark) => MARK[m.color ?? ""] ?? "var(--amber)";

// "state-gate/check" for features.….state-gate/check; a .sql file by its name.
const short = (b: { id: string; kind?: string; ns?: string; name?: string }) => {
  if (b.id.startsWith("file:")) return b.id.split("/").pop()!;
  const slash = b.id.lastIndexOf("/");
  return `${b.id.slice(0, slash).split(".").pop()}/${b.id.slice(slash + 1)}`;
};
// A var as Claude may name it (whole, or the end of its namespace) is this id.
const same = (v: string, id: string) => v === id || id.endsWith(`.${v}`) || (id.startsWith("file:") && id.endsWith(`/${v.replace(/^file:/, "")}`));

// ---- Where the data comes from: the runner, or the form's demo ----

type Source = { get: (id: string) => Promise<Got>; map: (id: string, up: number, down: number) => Promise<MapData | { error: string }> };
const Src = createContext<Source | null>(null);

function liveSource(base: string): Source {
  const cache = new Map<string, Promise<any>>();
  const ask = (q: string) => {
    let p = cache.get(q);
    if (!p) {
      p = fetch(`${base}${q}`).then((r) => r.json()).catch((e) => { cache.delete(q); return { error: String(e?.message ?? e) }; });
      cache.set(q, p);
    }
    return p;
  };
  return {
    get: (id) => ask(`&var=${encodeURIComponent(id)}`),
    map: (id, up, down) => ask(`&var=${encodeURIComponent(id)}&map=1&up=${up}&down=${down}`),
  };
}

// The demo: { "<id>": { file, row, code, private?, calls?: ["<id>"] } }. Where each call stands, the
// callers and the docs are found in the code, the way clj-kondo would give them.
function demoSource(demo: Record<string, any>): Source {
  const ids = Object.keys(demo);
  const resolve = (s: string) => ids.find((id) => same(s, id));
  const reEsc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const brief = (id: string): Brief => {
    const v = demo[id];
    const slash = id.lastIndexOf("/");
    const sql = id.startsWith("file:");
    return { id, kind: sql ? "sql" : "var", ns: sql ? undefined : id.slice(0, slash), name: sql ? id.split("/").pop()! : id.slice(slash + 1), file: v.file, folder: String(v.file).split("/").slice(0, -1).join("/"), row: v.row ?? 1, private: v.private };
  };
  const docOf = (id: string) => {
    const code: string = demo[id].code;
    if (id.startsWith("file:")) return code.startsWith("--") ? code.split("\n")[0].replace(/^--\s*/, "") : undefined;
    return code.match(/^\s+"((?:\\.|[^"\\])*)"/m)?.[1].replace(/\\"/g, '"').replace(/\s*\n\s*/g, " ");
  };
  const where = (from: string, to: string): At[] => {
    const lines: string[] = demo[from].code.split("\n");
    const name = to.startsWith("file:") ? `:${to.split("/").pop()!.replace(/\.sql$/, "").replace(/_/g, "-")}` : to.slice(to.lastIndexOf("/") + 1);
    const re = new RegExp(`(^|[\\s(\\[{#'@])((?:[\\w.\\-]+/)?${reEsc(name)})(?=$|[\\s)\\]}])`, "g");
    const out: At[] = [];
    lines.forEach((l, i) => {
      if (i === 0) return;
      for (const m of l.matchAll(re)) {
        const col = m.index! + m[1].length + 1 + (m[2].length - name.length);
        out.push({ row: demo[from].row + i, col, endCol: col + name.length });
      }
    });
    return out;
  };
  const callsOf = (id: string) => list(demo[id]?.calls).filter((t) => demo[t]);
  const get = async (s: string): Promise<Got> => {
    const id = resolve(s);
    if (!id) return { error: `no var ${s} in the demo` };
    const v = demo[id], code: string = v.code;
    const callers: Caller[] = ids.filter((f) => callsOf(f).includes(id)).map((f) => ({ ...brief(f), lines: where(f, id).map((a) => ({ row: a.row, text: demo[f].code.split("\n")[a.row - demo[f].row].trim() })) }));
    return {
      ...brief(id), endRow: (v.row ?? 1) + code.split("\n").length - 1, lang: String(v.file).split(".").pop(), args: [], doc: docOf(id), code,
      callees: callsOf(id).map((t) => ({ ...brief(t), doc: docOf(t), at: where(id, t) })), callers,
    };
  };
  const map = async (s: string, up: number, down: number) => {
    const start = resolve(s);
    if (!start) return { error: `no var ${s} in the demo` };
    const dist = new Map<string, number>([[start, 0]]);
    const walk = (steps: number, sign: number, next: (id: string) => string[]) => {
      let front = [start];
      for (let k = 1; k <= steps && front.length; k++) {
        const found = [...new Set(front.flatMap(next))].filter((n) => !dist.has(n));
        found.forEach((n) => dist.set(n, sign * k));
        front = found;
      }
    };
    walk(down, 1, callsOf);
    walk(up, -1, (id) => ids.filter((f) => callsOf(f).includes(id)));
    const edges: [string, string][] = [];
    for (const a of dist.keys()) for (const b of callsOf(a)) if (dist.has(b)) edges.push([a, b]);
    return { start, nodes: [...dist].map(([id, n]) => ({ ...brief(id), dist: n })), edges, cut: {} };
  };
  return { get, map };
}

// One var's detail, read through the source; the last one stays shown while the same var is read again.
function useDetail(id: string | null): Got | null {
  const src = useContext(Src)!;
  const [got, setGot] = useState<{ id: string; d: Got } | null>(null);
  useEffect(() => {
    if (!id) return;
    let gone = false;
    src.get(id).then((d) => { if (!gone) setGot({ id, d }); });
    return () => { gone = true; };
  }, [id, src]);
  return id && got?.id === id ? got.d : null;
}

// ---- Code with its calls as links ----

type Link = { start: number; end: number; to: string };
type Seg = { text: string; color?: string; italic?: boolean; to?: string };
// A line's pieces: Shiki's colours, cut where a call's link starts and ends.
function segments(text: string, tokens: Token[] | undefined, links: Link[]): Seg[] {
  const base: Seg[] = tokens?.length ? tokens.map((t) => ({ text: t.content, color: t.color, italic: !!((t.fontStyle ?? 0) & 1) })) : [{ text }];
  if (!links.length) return base;
  const cuts = new Set<number>([0, text.length]);
  for (const l of links) { cuts.add(l.start); cuts.add(l.end); }
  const out: Seg[] = [];
  let pos = 0;
  for (const s of base) {
    const end = pos + s.text.length;
    const inside = [...cuts].filter((c) => c > pos && c < end).sort((a, b) => a - b);
    let from = pos;
    for (const c of [...inside, end]) {
      const piece = s.text.slice(from - pos, c - pos);
      const link = links.find((l) => from >= l.start && from < l.end);
      if (piece) out.push({ ...s, text: piece, to: link?.to });
      from = c;
    }
    pos = end;
  }
  return out;
}

// The rows a long var shows: its first 3, every row with a call or a mark (± 1), and its last.
function shownRows(d: Detail, keep: number[]): Set<number> {
  const rows = new Set<number>();
  const last = d.row + d.code.split("\n").length - 1;
  for (let r = d.row; r < d.row + 3; r++) rows.add(r);
  rows.add(last);
  for (const r of [...d.callees.flatMap((c) => c.at.map((a) => a.row)), ...keep]) for (let x = r - 1; x <= r + 1; x++) if (x >= d.row && x <= last) rows.add(x);
  return rows;
}

// The code of a var. under: what goes under a row (nests); open: "row to" keys of opened calls.
function Code({ d, onCall, open, under, marks }: { d: Detail; onCall: (to: string, row: number) => void; open?: Set<string>; under?: (row: number) => React.ReactNode; marks: Mark[] }) {
  const [tokens, setTokens] = useState<Token[][] | null>(null);
  useEffect(() => {
    let gone = false;
    codeTokens(d.code, d.lang ?? "clj").then((t) => { if (!gone) setTokens(t); }).catch(() => {});
    return () => { gone = true; };
  }, [d.code, d.lang]);
  const lines = d.code.split("\n");
  const byRow = new Map<number, Link[]>();
  for (const c of d.callees) for (const a of c.at) {
    const l = byRow.get(a.row) ?? [];
    byRow.set(a.row, l);
    l.push({ start: a.col - 1, end: a.endCol - 1, to: c.id });
  }
  const lineMarks = marks.filter((m) => typeof m.line === "number");
  const long = lines.length > LONG;
  const [unfolded, setUnfolded] = useState<Set<number>>(new Set());
  useEffect(() => setUnfolded(new Set()), [d.id]);
  const shown = long ? shownRows(d, lineMarks.map((m) => m.line!)) : null;
  const out: React.ReactNode[] = [];
  for (let i = 0; i < lines.length; ) {
    const row = d.row + i;
    if (shown && !shown.has(row)) {
      let j = i;
      while (j < lines.length && !shown.has(d.row + j)) j++;
      const at = row;
      // A gap of a line or two shows as itself: its fold would take as much room.
      if (unfolded.has(at) || j - i < 3) {
        for (let k = i; k < j; k++) out.push(<Line key={k} row={d.row + k} text={lines[k]} tokens={tokens?.[k]} links={byRow.get(d.row + k) ?? []} open={open} onCall={onCall} marks={lineMarks} />);
      } else {
        out.push(<button type="button" key={`f${i}`} className="ca-fold" onClick={() => setUnfolded(new Set([...unfolded, at]))}>… {j - i} line{j - i > 1 ? "s" : ""}</button>);
      }
      i = j;
      continue;
    }
    out.push(<Line key={i} row={row} text={lines[i]} tokens={tokens?.[i]} links={byRow.get(row) ?? []} open={open} onCall={onCall} marks={lineMarks} />);
    const extra = under?.(row);
    if (extra) out.push(<React.Fragment key={`u${i}`}>{extra}</React.Fragment>);
    i++;
  }
  return <div className="ca-code">{out}</div>;
}

function Line({ row, text, tokens, links, open, onCall, marks }: { row: number; text: string; tokens?: Token[]; links: Link[]; open?: Set<string>; onCall: (to: string, row: number) => void; marks: Mark[] }) {
  const m = marks.find((x) => x.line === row);
  return (
    <>
      <div className={`ca-ln${m ? " marked" : ""}`} style={m ? ({ "--mk": markColor(m) } as React.CSSProperties) : undefined}>
        <span className="ca-no">{row}</span>
        <span className="ca-tx">
          {segments(text, tokens, links).map((s, k) => s.to ? (
            <button type="button" key={k} className={`ca-call${open?.has(`${row} ${s.to}`) ? " open" : ""}`} onClick={() => onCall(s.to!, row)} title={s.to.replace(/^file:/, "")}>{s.text}</button>
          ) : (
            <span key={k} style={{ color: s.color, fontStyle: s.italic ? "italic" : undefined }}>{s.text}</span>
          ))}
          {!text && " "}
        </span>
      </div>
      {m?.note && <div className="ca-note" style={{ color: markColor(m) }}>● {m.note}</div>}
    </>
  );
}

// A var's card: its name and place, the marks on it, then its code.
function Card({ id, marks, head, onCall, open, under }: { id: string; marks: Mark[]; head?: (d: Detail) => React.ReactNode; onCall: (to: string, row: number) => void; open?: Set<string>; under?: (row: number) => React.ReactNode }) {
  const got = useDetail(id);
  if (!got) return <div className="ca-card"><p className="f-note ca-pad">Reading…</p></div>;
  if ("error" in got) return <div className="ca-card"><p className="f-note cl-err">{got.error}</p></div>;
  const d = got;
  const mine = marks.filter((m) => same(m.var, d.id));
  return (
    <div className="ca-card">
      <div className="ca-hd">
        <b title={d.id.replace(/^file:/, "")}>{short(d)}</b>
        {d.private && <i>private</i>}
        {d.varKind && d.varKind !== "fn" && <i>{d.varKind}</i>}
        <span>{d.file}:{d.row}</span>
      </div>
      {mine.filter((m) => typeof m.line !== "number" && m.note).map((m) => <div key={m.id} className="ca-note ca-pad" style={{ color: markColor(m) }}>● {m.note}</div>)}
      {head?.(d)}
      <Code d={d} onCall={onCall} open={open} under={under} marks={mine} />
    </div>
  );
}

// ---- A · Unfold ----

// A card whose calls open nested cards under their line (each call its own nest; again closes it).
function UnfoldCard({ id, marks, top, reroot }: { id: string; marks: Mark[]; top?: boolean; reroot?: (id: string) => void }) {
  const [nests, setNests] = useState<{ row: number; to: string }[]>([]);
  useEffect(() => setNests([]), [id]);
  const toggle = (to: string, row: number) => setNests((n) => (n.some((x) => x.row === row && x.to === to) ? n.filter((x) => !(x.row === row && x.to === to)) : [...n, { row, to }]));
  return (
    <Card id={id} marks={marks} onCall={toggle} open={new Set(nests.map((n) => `${n.row} ${n.to}`))}
      head={top ? (d) => (
        <div className="ca-up">
          <span className="k">called by</span>
          {d.callers.length ? d.callers.map((c) => <button type="button" key={c.id} className="cl-chip" title={c.id} onClick={() => reroot?.(c.id)}>{short(c)}</button>) : <span className="f-note">nobody in this project</span>}
        </div>
      ) : undefined}
      under={(row) => {
        const here = nests.filter((n) => n.row === row);
        return here.length ? here.map((n) => <div key={`${n.row} ${n.to}`} className="ca-nest"><UnfoldCard id={n.to} marks={marks} /></div>) : null;
      }} />
  );
}

// ---- B · Neighbours ----

const MANY = 30;
function Neighbours({ start, marks }: { start: string; marks: Mark[] }) {
  const [trail, setTrail] = useState<string[]>([start]);
  useEffect(() => setTrail([start]), [start]);
  const here = trail[trail.length - 1];
  const got = useDetail(here);
  const go = (id: string) => setTrail((t) => (t[t.length - 1] === id ? t : [...t, id]));
  const [all, setAll] = useState(false);
  useEffect(() => setAll(false), [here]);
  const d = got && !("error" in got) ? got : null;
  const dot = (id: string) => { const m = marks.find((x) => same(x.var, id)); return m ? <i className="ca-dot" style={{ background: markColor(m) }} title={m.note} /> : null; };
  const callers = d ? (all ? d.callers : d.callers.slice(0, MANY)) : [];
  return (
    <>
      <div className="ca-trail">
        <span className="k">path</span>
        {trail.map((t, i) => (
          <React.Fragment key={i}>
            {i > 0 && <span className="ca-sep">→</span>}
            <button type="button" className={`cl-chip${i === trail.length - 1 ? " on" : ""}`} title={t} onClick={() => setTrail(trail.slice(0, i + 1))}>{short({ id: t })}</button>
          </React.Fragment>
        ))}
      </div>
      <div className="ca-hood">
        <div className="ca-side ca-left">
          <div className="ca-lab">← called by{d ? ` · ${d.callers.length}` : ""}</div>
          {d && !d.callers.length && <p className="f-note">nobody in this project</p>}
          {callers.map((c) => (
            <button type="button" key={c.id} className="ca-item" onClick={() => go(c.id)} title={c.id}>
              <b>{dot(c.id)}{short(c)}</b><span>{c.file.split("/").pop()}:{c.row}</span>
              {c.lines.map((l) => <code key={l.row}><em>{l.row}</em>{l.text}</code>)}
            </button>
          ))}
          {d && !all && d.callers.length > MANY && <button type="button" className="ca-fold" onClick={() => setAll(true)}>… {d.callers.length - MANY} more callers</button>}
        </div>
        <div className="ca-mid"><Card id={here} marks={marks} onCall={(to) => go(to)} /></div>
        <div className="ca-side ca-right">
          <div className="ca-lab">calls →{d ? ` · ${d.callees.length}` : ""}</div>
          {d && !d.callees.length && <p className="f-note">no project vars</p>}
          {d?.callees.map((c) => (
            <button type="button" key={c.id} className="ca-item" onClick={() => go(c.id)} title={c.id.replace(/^file:/, "")}>
              <b>{dot(c.id)}{short(c)}</b><span>{c.file.split("/").pop()}:{c.row}</span>
              {c.doc && <code className="doc">{c.doc}</code>}
            </button>
          ))}
        </div>
      </div>
    </>
  );
}

// ---- C · Map ----

// A line through points with its corners rounded.
function rounded(pts: [number, number][], r = 7): string {
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const [px, py] = pts[i - 1], [cx, cy] = pts[i], [nx, ny] = pts[i + 1];
    const k1 = Math.min(r, Math.hypot(cx - px, cy - py) / 2) / (Math.hypot(cx - px, cy - py) || 1);
    const k2 = Math.min(r, Math.hypot(nx - cx, ny - cy) / 2) / (Math.hypot(nx - cx, ny - cy) || 1);
    d += ` L${cx - (cx - px) * k1},${cy - (cy - py) * k1} Q${cx},${cy} ${cx + (nx - cx) * k2},${cy + (ny - cy) * k2}`;
  }
  const [lx, ly] = pts[pts.length - 1];
  return `${d} L${lx},${ly}`;
}

function CallMap({ start, marks, up, down, setUp, setDown }: { start: string; marks: Mark[]; up: number; down: number; setUp: (n: number) => void; setDown: (n: number) => void }) {
  const src = useContext(Src)!;
  const [map, setMap] = useState<{ key: string; m: MapData | { error: string } } | null>(null);
  const key = `${start} ${up} ${down}`;
  useEffect(() => {
    let gone = false;
    src.map(start, up, down).then((m) => { if (!gone) setMap({ key, m }); });
    return () => { gone = true; };
  }, [key, src]);
  const m = map?.m;
  const [sel, setSel] = useState<string | null>(null);
  useEffect(() => setSel(null), [start]);
  const data = m && !("error" in m) ? m : null;
  const picked = sel ?? data?.start ?? null;
  // Folders in the order met (start first), each its colour.
  const folders = useMemo(() => {
    const f: string[] = [];
    for (const n of [...(data?.nodes ?? [])].sort((a, b) => Math.abs(a.dist) - Math.abs(b.dist))) if (!f.includes(n.folder)) f.push(n.folder);
    return f;
  }, [data]);
  const colorOf = (n: Brief) => COLORS[folders.indexOf(n.folder) % COLORS.length];
  const cols = useMemo(() => {
    if (!data) return [];
    const ks = [...new Set(data.nodes.map((n) => n.dist))].sort((a, b) => a - b);
    return ks.map((k) => ({ k, nodes: data.nodes.filter((n) => n.dist === k).sort((a, b) => a.folder.localeCompare(b.folder) || a.name.localeCompare(b.name)) }));
  }, [data]);

  // The arrows, from where the boxes landed: measured after layout and whenever the block's size changes.
  const wrap = useRef<HTMLDivElement>(null);
  const boxes = useRef(new Map<string, HTMLElement>());
  const [paths, setPaths] = useState<{ a: string; b: string; d: string; back?: boolean }[]>([]);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const measure = () => {
    const w = wrap.current;
    if (!w || !data) return;
    const r0 = w.getBoundingClientRect();
    setSize({ w: r0.width, h: r0.height });
    const out: { a: string; b: string; d: string; back?: boolean }[] = [];
    const rects = new Map([...boxes.current].map(([id, el]) => [id, el.getBoundingClientRect()]));
    const floor = Math.max(0, ...[...rects.values()].map((r) => r.bottom - r0.top));
    let backs = 0;
    for (const [a, b] of data.edges) {
      const ra = rects.get(a), rb = rects.get(b);
      if (!ra || !rb) continue;
      const x = (v: number) => v - r0.left, y = (v: number) => v - r0.top;
      if (rb.left > ra.right) {
        const x1 = x(ra.right), y1 = y(ra.top + ra.height / 2), x2 = x(rb.left) - 2, y2 = y(rb.top + rb.height / 2), mx = (x1 + x2) / 2;
        out.push({ a, b, d: `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}` });
        continue;
      }
      // Back to the same column or one to the left: a curve below every box, out of the caller's right
      // side down the gap after its column, along under the map, and up the gap beside the callee.
      const yA = y(ra.top + ra.height / 2), yB = y(rb.top + rb.height / 2), low = floor + 12 + (backs % 4) * 7;
      const left = rb.right < ra.left - 2; // in a column to the left: into its right side
      const gB = left ? x(rb.right) + 7 : x(rb.left) - 7;
      const pts: [number, number][] = [[x(ra.right), yA], [x(ra.right) + 7, yA], [x(ra.right) + 7, low], [gB, low], [gB, yB], [left ? x(rb.right) + 2 : x(rb.left) - 2, yB]];
      backs++;
      out.push({ a, b, d: rounded(pts), back: true });
    }
    setPaths(out);
  };
  useLayoutEffect(measure, [data]);
  useEffect(() => {
    const w = wrap.current;
    if (!w) return;
    const ro = new ResizeObserver(() => measure());
    ro.observe(w);
    return () => ro.disconnect();
  }, [!!data]);

  if (!m) return <p className="f-note ca-pad">Reading…</p>;
  if ("error" in m) return <p className="f-note cl-err">{m.error}</p>;
  const hot = (e: { a: string; b: string }) => !!picked && (e.a === picked || e.b === picked);
  const markOf = (id: string) => marks.find((x) => same(x.var, id));
  const step = (n: number, set: (n: number) => void, by: number) => set(Math.max(0, Math.min(8, n + by)));
  return (
    <>
      <div className="ca-depth">
        <span className="k">callers</span>
        <button type="button" className="cl-full" disabled={up === 0} onClick={() => step(up, setUp, -1)}>−</button><b>{up}</b><button type="button" className="cl-full" disabled={up === 8} onClick={() => step(up, setUp, 1)}>+</button>
        <span className="k">callees</span>
        <button type="button" className="cl-full" disabled={down === 0} onClick={() => step(down, setDown, -1)}>−</button><b>{down}</b><button type="button" className="cl-full" disabled={down === 8} onClick={() => step(down, setDown, 1)}>+</button>
        <span className="ca-count">{data!.nodes.length} vars · {data!.edges.length} calls</span>
      </div>
      <div className="ca-legend">
        {folders.map((f, i) => <span key={f} style={{ "--c": COLORS[i % COLORS.length] } as React.CSSProperties}><i />{f || "(top)"}</span>)}
      </div>
      <div className="ca-wrap" ref={wrap}>
        <svg className="ca-arrows" width={size.w} height={size.h}>
          <defs>
            {["muted", "accent"].map((c) => <marker key={c} id={`ca-a-${c}`} viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0L8,4L0,8z" fill={`var(--${c})`} /></marker>)}
          </defs>
          {paths.filter((p) => !hot(p)).map((p) => <path key={`${p.a}>${p.b}`} d={p.d} className={`e${p.back ? " back" : ""}`} markerEnd="url(#ca-a-muted)" />)}
          {paths.filter(hot).map((p) => <path key={`${p.a}>${p.b}`} d={p.d} className={`e hot${p.back ? " back" : ""}`} markerEnd="url(#ca-a-accent)" />)}
        </svg>
        <div className="ca-cols">
          {cols.map((c) => (
            <div key={c.k} className="ca-col">
              <div className="ca-dl">{c.k < 0 ? `${-c.k} up` : c.k === 0 ? "start" : `${c.k} down`}</div>
              <div className="ca-stack">
              {c.nodes.map((n) => {
                const mk = markOf(n.id);
                return (
                  <button type="button" key={n.id} ref={(el) => { if (el) boxes.current.set(n.id, el); else boxes.current.delete(n.id); }}
                    className={`ca-box${n.id === picked ? " sel" : ""}${n.id === data!.start ? " start" : ""}`} style={{ "--c": colorOf(n) } as React.CSSProperties}
                    onClick={() => setSel(n.id)} title={`${n.id.replace(/^file:/, "")}${mk?.note ? `\n● ${mk.note}` : ""}`}>
                    <b>{mk && <i className="ca-dot" style={{ background: markColor(mk) }} />}{n.name}{n.private && <> <em>private</em></>}</b>
                    <span>{n.file.split("/").pop()}:{n.row}</span>
                  </button>
                );
              })}
              {data!.cut[String(c.k)] ? <div className="f-note ca-cut">+ {data!.cut[String(c.k)]} more</div> : null}
              </div>
            </div>
          ))}
        </div>
      </div>
      {picked && <Card id={picked} marks={marks} onCall={(to) => setSel(to)} />}
    </>
  );
}

// ---- The form ----

const VIEWS: { v: View; label: string; hint: string }[] = [
  { v: "unfold", label: "A · Unfold", hint: "Click a blue call: its code opens right under that line; click again to close. \"called by\" goes up." },
  { v: "neighbours", label: "B · Neighbours", hint: "Who calls it on the left (with the calling line), what it calls on the right (with its doc line). Click anything to move there." },
  { v: "map", label: "C · Map", hint: "Columns are steps away from the start. Colour is the folder. Click a box: its code shows below and its arrows light up." },
];
const viewOf = (v: unknown): View => (v === "neighbours" || v === "map" ? v : "unfold");
const depth = (v: unknown, d: number) => (typeof v === "number" && v >= 0 ? Math.min(8, Math.floor(v)) : d);

export function CallsForm({ data, chat }: { data: Data; chat?: string }) {
  const demo = data.demo && typeof data.demo === "object" ? data.demo : null;
  const root: string = typeof data.root === "string" ? data.root : "";
  const open: string = typeof data.open === "string" ? data.open.trim() : "";
  const live = !demo && !!root && !!chat;
  const base = `/api/chats/${chat}/calls?root=${encodeURIComponent(root)}`;
  // A new source (an empty cache) whenever the project's fingerprint changes.
  const [fp, setFp] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!live) return;
    let gone = false, known: string | undefined;
    let timer: ReturnType<typeof setTimeout>;
    const ask = async () => {
      if (!document.hidden) {
        try {
          const j = await fetch(`${base}${known ? `&fp=${known}` : ""}`).then((r) => r.json());
          if (gone) return;
          if (j.error) setError(j.error);
          else if (!j.same) { known = j.fp; setFp(j.fp); setError(null); }
        } catch {}
      }
      if (!gone) timer = setTimeout(ask, POLL_MS);
    };
    ask();
    return () => { gone = true; clearTimeout(timer); };
  }, [live, base]);
  const src = useMemo<Source | null>(() => (demo ? demoSource(demo) : live && fp ? liveSource(base) : null), [demo, live, base, fp]);

  const [view, setView] = useState<View>(viewOf(data.view));
  useEffect(() => setView(viewOf(data.view)), [data.view]);
  const [rootVar, setRootVar] = useState(open);
  useEffect(() => setRootVar(open), [open]);
  const [up, setUp] = useState(depth(data.up, 2));
  const [down, setDown] = useState(depth(data.down, 3));
  useEffect(() => setUp(depth(data.up, 2)), [data.up]);
  useEffect(() => setDown(depth(data.down, 3)), [data.down]);
  const marks: Mark[] = list(data.marks).filter((m) => m && typeof m.var === "string");

  if (error) return <p className="f-note cl-err">{error}</p>;
  if (!open) return <p className="f-note">No var to start from (open: "ns/var").</p>;
  if (!src) return <p className="f-note">{live ? "Reading the project with clj-kondo…" : "Open this chat to see its calls."}</p>;
  const name = demo ? "demo" : root.split("/").filter(Boolean).pop() ?? "project";
  const hint = VIEWS.find((x) => x.v === view)!.hint;
  return (
    <Src.Provider value={src}>
      <div className="ca-form">
        <div className="ca-head">
          <div className="ca-tabs" role="tablist">
            {VIEWS.map((x) => <button type="button" role="tab" aria-selected={view === x.v} key={x.v} className={view === x.v ? "on" : ""} onClick={() => setView(x.v)}>{x.label}</button>)}
          </div>
          <span className="ca-where" title={open}>{name} · {short({ id: open })}</span>
        </div>
        <p className="ca-hint">{hint}</p>
        <div className="ca-body">
          {view === "unfold" && (
            <>
              {rootVar !== open && <button type="button" className="ca-fold ca-back" onClick={() => setRootVar(open)}>↺ back to {short({ id: open })}</button>}
              <UnfoldCard key={rootVar} id={rootVar} marks={marks} top reroot={setRootVar} />
            </>
          )}
          {view === "neighbours" && <Neighbours start={open} marks={marks} />}
          {view === "map" && <CallMap start={open} marks={marks} up={up} down={down} setUp={setUp} setDown={setDown} />}
        </div>
      </div>
    </Src.Provider>
  );
}
