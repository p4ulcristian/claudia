// The clojure form (src/forms.ts): a project's namespaces as chalk bubbles in layers, entry points
// left, leaves right, arrows for :require (one going back closes a circle: red). A prefix with
// namespaces under it is one bubble that opens on click. A namespace lists its vars below the map, a
// var opens its source, an arrow lists the functions that cross it. Read live through the runner
// (src/clojure.ts, clj-kondo), asked again every few seconds with the project's fingerprint.
import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { codeTokens, type Token } from "./shiki";

type Data = Record<string, any>;
type Ns = { name: string; vars: number; requires: string[] };
type Var = { name: string; args: string[]; doc?: string; private?: boolean; kind: string; row?: number; code?: string };
type Detail = { doc?: string; vars: Var[]; external: string[] };
type Used = { ns: string; name: string; count: number; from: string[] };
type Source = { code: string; row: number; lang?: string };
// A bubble: a prefix (everything under key) or, exact, one namespace only.
type Node = { key: string; label: string; exact: boolean; group: boolean; count: number; vars: number; layer: number; slot: number; x: number; y: number; r: number; color: string };
type Dir = "across" | "down";
type Edge = { from: string; to: string; pairs: number; back: boolean };

const list = (v: unknown): any[] => (Array.isArray(v) ? v : []);
const POLL_MS = 4000;
// Across: layers are columns (COL_W apart), bubbles in a column ROW_H apart. Down: layers are rows
// (LAYER_H apart), bubbles in a row NODE_W apart.
const COL_W = 180;
const ROW_H = 84;
const LAYER_H = 120;
const NODE_W = 150;
const PAD = 40;
const COLORS = ["var(--c-teal)", "var(--c-amber)", "var(--c-sky)", "var(--c-pink)", "var(--c-lime)", "var(--c-violet)", "var(--c-orange)", "var(--c-cyan)", "var(--c-red)", "var(--c-indigo)", "var(--c-yellow)", "var(--c-green)"];
const MARK: Record<string, string> = { red: "var(--red)", green: "var(--green)", amber: "var(--amber)", blue: "var(--blue)", violet: "var(--violet)", grey: "var(--muted)" };

const under = (n: string, p: string) => !p || n === p || n.startsWith(p + ".");
const parent = (n: string) => (n.includes(".") ? n.slice(0, n.lastIndexOf(".")) : "");
const inNode = (n: string, node: { key: string; exact: boolean }) => (node.exact ? n === node.key : under(n, node.key));

// The bubbles and arrows of the map at prefix at, laid out in layers.
export function layout(all: Ns[], at: string): { nodes: Node[]; edges: Edge[]; layers: number; rows: number } {
  const members = all.filter((n) => under(n.name, at));
  const byKey = new Map<string, Node>();
  const nodeOf = (name: string): string => {
    if (name === at) return `=${at}`;
    const seg = name.slice(at ? at.length + 1 : 0).split(".")[0];
    return at ? `${at}.${seg}` : seg;
  };
  for (const n of members) {
    const id = nodeOf(n.name);
    const exact = id.startsWith("=");
    const key = exact ? at : id;
    const node = byKey.get(id) ?? { key, label: key.slice(exact || !at ? 0 : at.length + 1).split(".").pop()!, exact, group: false, count: 0, vars: 0, layer: 0, slot: 0, x: 0, y: 0, r: 0, color: "" };
    byKey.set(id, node);
    node.count++;
    node.vars += n.vars;
    if (!exact && n.name !== key) node.group = true;
  }
  const ids = [...byKey.keys()].sort();
  ids.forEach((id, i) => { byKey.get(id)!.color = COLORS[i % COLORS.length]; });
  const pairs = new Map<string, number>();
  for (const n of members) for (const r of n.requires) {
    if (!under(r, at)) continue;
    const a = nodeOf(n.name), b = nodeOf(r);
    if (a !== b && byKey.has(b)) pairs.set(`${a}\u0000${b}`, (pairs.get(`${a}\u0000${b}`) ?? 0) + 1);
  }
  const out = new Map<string, string[]>(ids.map((id) => [id, []]));
  for (const k of pairs.keys()) { const [a, b] = k.split("\u0000"); out.get(a)!.push(b); }
  // Arrows that close a circle (found going depth-first, sources first) don't count for the layers.
  const back = new Set<string>();
  const state = new Map<string, 1 | 2>();
  const hasIn = new Set([...pairs.keys()].map((k) => k.split("\u0000")[1]));
  const visit = (v: string) => {
    state.set(v, 1);
    for (const w of out.get(v)!) {
      if (state.get(w) === 1) back.add(`${v}\u0000${w}`);
      else if (!state.has(w)) visit(w);
    }
    state.set(v, 2);
  };
  for (const id of [...ids.filter((i) => !hasIn.has(i)), ...ids]) if (!state.has(id)) visit(id);
  // Longest path from an entry point: each arrow goes at least one column right.
  const layer = new Map<string, number>(ids.map((id) => [id, 0]));
  for (let pass = 0, moved = true; moved && pass < ids.length; pass++) {
    moved = false;
    for (const k of pairs.keys()) {
      if (back.has(k)) continue;
      const [a, b] = k.split("\u0000");
      if (layer.get(b)! < layer.get(a)! + 1) { layer.set(b, layer.get(a)! + 1); moved = true; }
    }
  }
  const cols: string[][] = [];
  for (const id of ids) (cols[layer.get(id)!] ??= []).push(id);
  // Order inside a column by where its neighbours sit, a few sweeps each way.
  const pos = new Map<string, number>();
  const place = () => cols.forEach((c) => c.forEach((id, i) => pos.set(id, i - (c.length - 1) / 2)));
  place();
  const near = (id: string, dir: 1 | -1) => [...pairs.keys()].map((k) => k.split("\u0000")).filter(([a, b]) => (dir === 1 ? b === id : a === id)).map(([a, b]) => pos.get(dir === 1 ? a : b)!);
  for (let s = 0; s < 4; s++) {
    const dir = s % 2 === 0 ? 1 : -1;
    for (const c of dir === 1 ? cols : [...cols].reverse()) {
      if (!c) continue;
      const at = new Map(c.map((id) => { const n = near(id, dir); return [id, n.length ? n.reduce((x, y) => x + y, 0) / n.length : pos.get(id)!]; }));
      c.sort((a, b) => at.get(a)! - at.get(b)!);
      place();
    }
  }
  const rows = Math.max(1, ...cols.map((c) => c?.length ?? 0));
  const nodes: Node[] = [];
  cols.forEach((c, li) => c?.forEach((id, i) => {
    const n = byKey.get(id)!;
    n.layer = li;
    n.slot = (rows - c.length) / 2 + i;
    n.r = 11 + Math.min(20, Math.sqrt(n.group ? n.count * 12 : n.vars) * 1.7);
    nodes.push(n);
  }));
  const edges = [...pairs].map(([k, count]) => { const [a, b] = k.split("\u0000"); return { from: a, to: b, pairs: count, back: back.has(k) }; });
  return { nodes, edges, layers: cols.length, rows };
}

// The node id layout() keys by: "=" + prefix for the namespace that is the prefix itself.
const idOf = (n: Node) => (n.exact ? `=${n.key}` : n.key);

// Where the bubbles sit, and the map's size, for a direction.
function place(nodes: Node[], layers: number, rows: number, dir: Dir): { W: number; H: number } {
  for (const n of nodes) {
    if (dir === "across") { n.x = PAD + 50 + n.layer * COL_W; n.y = PAD + n.slot * ROW_H + ROW_H / 2; }
    else { n.x = PAD + 60 + n.slot * NODE_W; n.y = PAD + 40 + n.layer * LAYER_H; }
  }
  return dir === "across"
    ? { W: PAD * 2 + 100 + (layers - 1) * COL_W, H: PAD * 2 + rows * ROW_H + 10 }
    : { W: PAD * 2 + 120 + (rows - 1) * NODE_W, H: PAD * 2 + 100 + (layers - 1) * LAYER_H };
}

type Box = { x: number; y: number; w: number; h: number };
// The window on the map for a zoom: k times closer around (cx, cy), kept inside the map.
function viewOf(frame: Box, z: { k: number; cx: number; cy: number } | null): Box {
  const k = z?.k ?? 1, w = frame.w / k, h = frame.h / k;
  const clamp = (c: number, lo: number, size: number, win: number) => Math.min(lo + size - win / 2, Math.max(lo + win / 2, c));
  const cx = clamp(z?.cx ?? frame.x + frame.w / 2, frame.x, frame.w, w), cy = clamp(z?.cy ?? frame.y + frame.h / 2, frame.y, frame.h, h);
  return { x: cx - w / 2, y: cy - h / 2, w, h };
}

// The direction whose map is closest in shape to the space it gets: the half screen is narrow and
// scrolls down (about 0.8 wide per tall), full screen is wide.
function bestDir(layers: number, rows: number, aim: number): Dir {
  const ratio = (d: Dir) => { const { W, H } = place([], layers, rows, d); return Math.abs(Math.log(W / H / aim)); };
  return ratio("down") < ratio("across") ? "down" : "across";
}

function edgePath(a: Node, b: Node, back: boolean, dir: Dir): string {
  if (dir === "down") {
    if (!back && b.layer > a.layer) {
      const y1 = a.y + a.r, y2 = b.y - b.r - 6, my = (y1 + y2) / 2;
      return `M${a.x} ${y1}C${a.x} ${my} ${b.x} ${my} ${b.x} ${y2}`;
    }
    // Same row or going back: an arc round the left.
    const lift = 40 + Math.abs(a.y - b.y) * 0.15 + Math.abs(a.x - b.x) * 0.1;
    const x1 = a.x - a.r, x2 = b.x - b.r - 6, y2 = b.y + (a.y === b.y ? 4 : 0);
    return `M${x1} ${a.y}C${Math.min(x1, x2) - lift} ${a.y} ${Math.min(x1, x2) - lift} ${y2} ${x2} ${y2}`;
  }
  if (!back && b.layer > a.layer) {
    const x1 = a.x + a.r, x2 = b.x - b.r - 6, mx = (x1 + x2) / 2;
    return `M${x1} ${a.y}C${mx} ${a.y} ${mx} ${b.y} ${x2} ${b.y}`;
  }
  // Same column or going back: an arc over the top.
  const lift = 40 + Math.abs(a.x - b.x) * 0.15 + Math.abs(a.y - b.y) * 0.1;
  const x1 = a.x, y1 = a.y - a.r, x2 = b.x + (a.x === b.x ? 4 : 0), y2 = b.y - b.r - 6;
  return `M${x1} ${y1}C${x1} ${Math.min(y1, y2) - lift} ${x2} ${Math.min(y1, y2) - lift} ${x2} ${y2}`;
}

const strip = (n: string, p: string) => (p && n.startsWith(p + ".") ? n.slice(p.length + 1) : n);

function Code({ src }: { src: Source }) {
  const [tokens, setTokens] = useState<Token[][] | null>(null);
  useEffect(() => {
    let gone = false;
    codeTokens(src.code, src.lang ?? "clj").then((t) => { if (!gone) setTokens(t); }).catch(() => {});
    return () => { gone = true; };
  }, [src.code]);
  const lines = src.code.split("\n");
  return (
    <pre className="cl-src">{lines.map((l, i) => (
      <div key={i}><span className="cl-no">{src.row + i}</span>{tokens?.[i] ? tokens[i].map((t, j) => <span key={j} style={{ color: t.color }}>{t.content}</span>) : l}</div>
    ))}</pre>
  );
}

export function ClojureForm({ data, chat }: { data: Data; chat?: string }) {
  const demo = data.demo && typeof data.demo === "object" ? data.demo : null;
  const root: string = typeof data.root === "string" ? data.root : "";
  const live = !demo && !!root && !!chat;
  const base = `/api/chats/${chat}/clojure?root=${encodeURIComponent(root)}`;
  const [all, setAll] = useState<Ns[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [at, setAt] = useState<string>(typeof data.at === "string" ? data.at : "");
  const [pick, setPick] = useState<string | null>(null); // a namespace
  const [varPick, setVarPick] = useState<string | null>(null);
  const [edge, setEdge] = useState<{ from: Node; to: Node } | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [src, setSrc] = useState<Source | null>(null);
  const [used, setUsed] = useState<Used[] | null>(null);
  const [hover, setHover] = useState<string | null>(null);
  // Full screen: the map and the panel side by side over the whole screen (Esc leaves).
  const box = useRef<HTMLDivElement>(null);
  const [full, setFull] = useState(false);
  // null: picked by the map's shape (bestDir); a click on the direction button, or Claude's direction, fixes it.
  const [chosenDir, setChosenDir] = useState<Dir | null>(data.direction === "down" || data.direction === "across" ? data.direction : null);
  useEffect(() => { if (data.direction === "down" || data.direction === "across") setChosenDir(data.direction); }, [data.direction]);
  useEffect(() => {
    const on = () => setFull(document.fullscreenElement === box.current && !!box.current);
    document.addEventListener("fullscreenchange", on);
    return () => document.removeEventListener("fullscreenchange", on);
  }, []);
  const toggleFull = () => (full ? document.exitFullscreen() : box.current?.requestFullscreen())?.catch(() => {});

  // Zoom and pan: the viewBox is a window on the map's measured bounds (so nothing drawn is cut off).
  // Pinch or Ctrl/Cmd + wheel zooms at the pointer (any wheel in full screen); a drag pans.
  const svgRef = useRef<SVGSVGElement>(null);
  const drawn = useRef<SVGGElement>(null);
  // The map spans the block's width at a fixed height (the map's at up to 1.1×), so a zoom shows more
  // to the sides instead of cutting at a narrower box.
  const mapbox = useRef<HTMLDivElement>(null);
  const [boxW, setBoxW] = useState(0);
  useEffect(() => {
    const el = mapbox.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setBoxW(el.clientWidth));
    ro.observe(el);
    return () => ro.disconnect();
  }, [!!all]);
  const [bounds, setBounds] = useState<Box | null>(null);
  // The zoom lives in a ref and moves the viewBox straight on the element (no re-render per wheel
  // step, chalk filters off while moving); zoomK follows once it rests, for the buttons.
  const zoom = useRef<{ k: number; cx: number; cy: number } | null>(null); // null: the whole map
  const [zoomK, setZoomK] = useState(1);
  const latest = useRef<{ frame: Box; vb: Box } | null>(null);
  const frameReq = useRef(0);
  const rest = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const apply = () => {
    cancelAnimationFrame(frameReq.current);
    frameReq.current = requestAnimationFrame(() => {
      const el = svgRef.current, l = latest.current;
      if (!el || !l) return;
      l.vb = viewOf(l.frame, zoom.current);
      el.setAttribute("viewBox", `${l.vb.x} ${l.vb.y} ${l.vb.w} ${l.vb.h}`);
      el.classList.add("moving");
      clearTimeout(rest.current);
      rest.current = setTimeout(() => { el.classList.remove("moving"); setZoomK(zoom.current?.k ?? 1); }, 160);
    });
  };
  const pan = useRef<{ x: number; y: number; cx: number; cy: number; s: number } | null>(null);
  const dragged = useRef(false);
  const zoomAt = (f: number, clientX?: number, clientY?: number) => {
    const el = svgRef.current, l = latest.current, ctm = el?.getScreenCTM();
    if (!el || !l || !ctm) return;
    const r = el.getBoundingClientRect();
    const pt = new DOMPoint(clientX ?? r.left + r.width / 2, clientY ?? r.top + r.height / 2).matrixTransform(ctm.inverse());
    const fx = (pt.x - l.vb.x) / l.vb.w, fy = (pt.y - l.vb.y) / l.vb.h;
    const k = Math.min(16, Math.max(1, (zoom.current?.k ?? 1) * f));
    const w = l.frame.w / k, h = l.frame.h / k;
    zoom.current = k === 1 ? null : { k, cx: pt.x - fx * w + w / 2, cy: pt.y - fy * h + h / 2 };
    apply();
  };
  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      if (!(e.ctrlKey || e.metaKey || document.fullscreenElement)) return;
      e.preventDefault();
      zoomAt(Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.002)), e.clientX, e.clientY);
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  }, [!!all]);

  // The namespaces, asked again every POLL_MS while the page is seen; { same } until a file changes.
  useEffect(() => {
    if (demo) { setAll(list(demo.namespaces)); return; }
    if (!live) return;
    let gone = false, fp: string | undefined;
    let timer: ReturnType<typeof setTimeout>;
    const ask = async () => {
      if (!document.hidden) {
        try {
          const j = await fetch(`${base}${fp ? `&fp=${fp}` : ""}`).then((r) => r.json());
          if (gone) return;
          if (j.error) setError(j.error);
          else if (!j.same) { fp = j.fp; setAll(j.namespaces); setError(null); }
        } catch {}
      }
      if (!gone) timer = setTimeout(ask, POLL_MS);
    };
    ask();
    return () => { gone = true; clearTimeout(timer); };
  }, [live, base, demo]);

  // A prefix with a single bubble opens straight into it.
  const view = useMemo(() => {
    if (!all) return null;
    let p = at;
    for (let v = layout(all, p); ; v = layout(all, p)) {
      if (v.nodes.length === 1 && v.nodes[0].group) { p = v.nodes[0].key; continue; }
      return { ...v, at: p };
    }
  }, [all, at]);
  const nodeById = useMemo(() => new Map((view?.nodes ?? []).map((n) => [idOf(n), n])), [view]);

  // What Claude opens: a namespace, ns/var, or "a -> b".
  const openNs = (ns: string, v: string | null = null) => {
    setEdge(null);
    setPick(ns);
    setVarPick(v);
    if (all && view && !view.nodes.some((n) => inNode(ns, n) && !n.group)) setAt(parent(ns));
  };
  useEffect(() => {
    const o = typeof data.open === "string" ? data.open.trim() : "";
    if (!o || !view) return;
    const arrow = o.split(/\s*->\s*/);
    if (arrow.length === 2) {
      const find = (k: string) => view.nodes.find((n) => n.key === k) ?? view.nodes.find((n) => under(k, n.key));
      const a = find(arrow[0]), b = find(arrow[1]);
      if (a && b) { setPick(null); setEdge({ from: a, to: b }); }
      return;
    }
    const slash = o.indexOf("/");
    openNs(slash > 0 ? o.slice(0, slash) : o, slash > 0 ? o.slice(slash + 1) : null);
  }, [data.open, !!view]);
  useEffect(() => { if (typeof data.at === "string") setAt(data.at); }, [data.at]);

  useEffect(() => {
    setDetail(null);
    if (!pick) return;
    if (demo) { const d = demo.vars?.[pick]; setDetail({ doc: d?.doc, vars: list(d?.vars), external: list(d?.external) }); return; }
    let gone = false;
    fetch(`${base}&ns=${encodeURIComponent(pick)}`).then((r) => r.json()).then((j) => { if (!gone) setDetail(j.error ? { vars: [], external: [], doc: j.error } : j); }).catch(() => {});
    return () => { gone = true; };
  }, [pick, base]);
  useEffect(() => {
    setSrc(null);
    if (!pick || !varPick) return;
    if (demo) { const v = list(demo.vars?.[pick]?.vars).find((x: Var) => x.name === varPick); if (v?.code) setSrc({ code: v.code, row: v.row ?? 1, lang: "clj" }); return; }
    let gone = false;
    fetch(`${base}&ns=${encodeURIComponent(pick)}&var=${encodeURIComponent(varPick)}`).then((r) => r.json()).then((j) => { if (!gone && !j.error) setSrc(j); }).catch(() => {});
    return () => { gone = true; };
  }, [pick, varPick, base]);
  useEffect(() => {
    setUsed(null);
    if (!edge) return;
    const { from, to } = edge;
    if (demo) {
      const found = new Map<string, Used>();
      for (const [f, by] of Object.entries<any>(demo.uses ?? {})) if (inNode(f, from)) for (const [t, names] of Object.entries<any>(by)) if (inNode(t, to)) for (const name of list(names)) {
        const u = found.get(`${t}/${name}`) ?? { ns: t, name, count: 0, from: [] as string[] };
        u.count++; u.from.push(f); found.set(`${t}/${name}`, u);
      }
      setUsed([...found.values()]);
      return;
    }
    let gone = false;
    fetch(`${base}&from=${encodeURIComponent(from.key)}&fx=${from.exact ? 1 : 0}&to=${encodeURIComponent(to.key)}&tx=${to.exact ? 1 : 0}`).then((r) => r.json()).then((j) => { if (!gone) setUsed(list(j.used)); }).catch(() => {});
    return () => { gone = true; };
  }, [edge?.from.key, edge?.to.key, edge?.from.exact, edge?.to.exact, base]);

  useLayoutEffect(() => {
    const b = drawn.current?.getBBox();
    setBounds(b && b.width ? { x: b.x - 14, y: b.y - 14, w: b.width + 28, h: b.height + 28 } : null);
    zoom.current = null;
    setZoomK(1);
  }, [view, chosenDir, full]);

  if (error) return <p className="f-note cl-err">{error}</p>;
  if (!view) return <p className="f-note">{live || demo ? "Reading the project with clj-kondo…" : "Open this chat to see its namespaces."}</p>;

  const marks = list(data.marks);
  const markOn = (n: Node) => marks.find((m) => typeof m.ns === "string" && inNode(m.ns, n));
  const pickNode = pick ? view.nodes.find((n) => inNode(pick, n)) : undefined;
  const focus = hover ? nodeById.get(hover) : pickNode;
  const edgeKey = (e: Edge) => `${e.from}>${e.to}`;
  const onEdge = (e: Edge) => !!edge && e.from === idOf(edge.from) && e.to === idOf(edge.to);
  const lit = (n: Node) => {
    if (edge) return idOf(n) === idOf(edge.from) || idOf(n) === idOf(edge.to);
    if (!focus) return true;
    const f = idOf(focus);
    return idOf(n) === f || view.edges.some((e) => (e.from === f && e.to === idOf(n)) || (e.to === f && e.from === idOf(n)));
  };
  const dir: Dir = chosenDir ?? bestDir(view.layers, view.rows, full ? Math.max(1, (innerWidth * 0.68) / Math.max(1, innerHeight - 60)) : 0.8);
  const { W, H } = place(view.nodes, view.layers, view.rows, dir);
  const frame = bounds ?? { x: 0, y: 0, w: W, h: H };
  const k = zoomK;
  const vb = viewOf(frame, zoom.current);
  latest.current = { frame, vb };
  const crumbs = view.at ? view.at.split(".") : [];
  const name = root.split("/").filter(Boolean).pop() ?? "project";

  return (
    <div className={`cl-form${full ? " full" : ""}`} ref={box}>
      <div className="cl-head">
        <button type="button" className="cl-crumb" onClick={() => { setAt(""); setPick(null); setEdge(null); }}>{demo ? "demo" : name}</button>
        {crumbs.map((c, i) => (
          <React.Fragment key={i}>
            <span className="cl-dot">.</span>
            <button type="button" className="cl-crumb" onClick={() => { setAt(crumbs.slice(0, i + 1).join(".")); setPick(null); setEdge(null); }}>{c}</button>
          </React.Fragment>
        ))}
        <span className="cl-count">{view.nodes.length} bubbles · {all?.filter((n) => under(n.name, view.at)).length} namespaces</span>
        <button type="button" className="cl-full" onClick={() => setChosenDir(dir === "down" ? "across" : "down")} title={`${chosenDir ? "Set by hand" : "Picked by the map's shape"}; click to lay it out ${dir === "down" ? "left to right" : "top to bottom"}`}>{dir === "down" ? "↓ down" : "→ across"}</button>
        {chosenDir && <button type="button" className="cl-full" onClick={() => setChosenDir(null)} title="Pick the direction by the map's shape again">auto</button>}
        <span className="cl-zoom">
          <button type="button" className="cl-full" onClick={() => zoomAt(1 / 1.4)} disabled={k === 1} title="Zoom out">−</button>
          <button type="button" className="cl-full" onClick={() => zoomAt(1.4)} title="Zoom in (or pinch, Ctrl + scroll)">+</button>
          {k > 1 && <button type="button" className="cl-full" onClick={() => { zoom.current = null; apply(); }} title="The whole map">fit</button>}
        </span>
        <button type="button" className="cl-full" onClick={toggleFull} title={full ? "Leave full screen (Esc)" : "Full screen"}>{full ? "✕ exit" : "⤢ full screen"}</button>
      </div>
      <div className="cl-mapbox" ref={mapbox}>
      <svg className={`cl-map${k > 1 ? " zoomed" : ""}`} ref={svgRef} viewBox={`${vb.x} ${vb.y} ${vb.w} ${vb.h}`} style={full || !boxW ? undefined : { height: frame.h * Math.min(1.1, boxW / frame.w) }}
        onPointerDown={(e) => {
          const ctm = svgRef.current?.getScreenCTM();
          if (!zoom.current || !ctm) return;
          pan.current = { x: e.clientX, y: e.clientY, cx: vb.x + vb.w / 2, cy: vb.y + vb.h / 2, s: 1 / ctm.a };
          dragged.current = false;
        }}
        onPointerMove={(e) => {
          const p = pan.current;
          if (!p) return;
          const dx = e.clientX - p.x, dy = e.clientY - p.y;
          if (!dragged.current && Math.hypot(dx, dy) < 4) return;
          if (!dragged.current) { dragged.current = true; svgRef.current?.setPointerCapture(e.pointerId); }
          if (zoom.current) { zoom.current = { ...zoom.current, cx: p.cx - dx * p.s, cy: p.cy - dy * p.s }; apply(); }
        }}
        onPointerUp={() => { pan.current = null; }}
        onClickCapture={(e) => { if (dragged.current) { e.stopPropagation(); dragged.current = false; } }}>
        <defs>
          {["muted", "blue", "green", "violet", "red"].map((c) => (
            <marker key={c} id={`cl-a-${c}`} viewBox="0 0 10 10" refX="8" refY="5" markerUnits="userSpaceOnUse" markerWidth="11" markerHeight="11" orient="auto"><path d="M0 0L10 5L0 10z" fill={`var(--${c})`} /></marker>
          ))}
        </defs>
        <g ref={drawn}>
        <g className="cl-edges">
          {view.edges.map((e) => {
            const a = nodeById.get(e.from), b = nodeById.get(e.to);
            if (!a || !b) return null;
            const f = focus && !edge ? idOf(focus) : null;
            const tone = onEdge(e) ? "violet" : f && e.from === f ? "blue" : f && e.to === f ? "green" : e.back ? "red" : "muted";
            const dim = (edge && !onEdge(e)) || (f && e.from !== f && e.to !== f);
            const d = edgePath(a, b, e.back, dir);
            return (
              <g key={edgeKey(e)} className={`cl-edge${dim ? " dim" : ""}`} onClick={() => { setPick(null); setVarPick(null); setEdge(onEdge(e) ? null : { from: a, to: b }); }}>
                <path d={d} className="hit" />
                <path d={d} className={`line ${tone}`} strokeWidth={1.4 + Math.log2(e.pairs + 1) * 0.9} strokeDasharray={e.back || tone === "green" ? "6 5" : undefined} markerEnd={`url(#cl-a-${tone})`} />
                <title>{`${strip(a.key, view.at) || a.label} → ${strip(b.key, view.at) || b.label}: ${e.pairs} require${e.pairs > 1 ? "s" : ""}${e.back ? " (closes a circle)" : ""}. Click for the functions it uses.`}</title>
              </g>
            );
          })}
        </g>
        {view.nodes.map((n) => {
          const m = markOn(n);
          const on = pickNode && idOf(pickNode) === idOf(n);
          return (
            <g key={idOf(n)} className={`cl-node${lit(n) ? "" : " dim"}${on ? " on" : ""}`} onMouseEnter={() => setHover(idOf(n))} onMouseLeave={() => setHover(null)}
              onClick={() => { setEdge(null); if (n.group) { setAt(n.key); setPick(null); setVarPick(null); } else if (pick === n.key) { setPick(null); setVarPick(null); } else { setPick(n.key); setVarPick(null); } }}>
              <circle cx={n.x} cy={n.y} r={n.r} className="body" style={{ stroke: n.color, fill: `color-mix(in srgb, ${n.color} 18%, transparent)` }} />
              {n.group && <circle cx={n.x} cy={n.y} r={n.r + 4} className="ring" style={{ stroke: n.color }} />}
              <text x={n.x} y={n.y + 4} className="num">{n.group ? n.count : n.vars}</text>
              <text x={n.x} y={n.y + n.r + (n.group ? 19 : 15)} className="lbl">{n.exact ? `${n.label} (itself)` : n.label}{n.group ? " ›" : ""}</text>
              {m && <circle cx={n.x + n.r * 0.72} cy={n.y - n.r * 0.72} r={5} style={{ fill: MARK[m.color] ?? "var(--amber)" }}><title>{m.note ?? m.ns}</title></circle>}
              <title>{`${n.key}${n.group ? `: ${n.count} namespaces, click to open` : `: ${n.vars} vars`}`}</title>
            </g>
          );
        })}
        </g>
      </svg>
      <div className="cl-legend">
        <span><b className="blue">──</b> requires</span><span><b className="green">- -</b> required by</span><span><b className="red">- -</b> closes a circle</span><span>◎ opens · number = namespaces / vars</span>
      </div>
      </div>
      <div className="cl-panel">
        {edge ? (
          <EdgePanel edge={edge} at={view.at} used={used} go={(ns, v) => openNs(ns, v)} />
        ) : pick ? (
          <NsPanel ns={pick} detail={detail} all={all ?? []} varPick={varPick} setVarPick={setVarPick} src={src} marks={marks.filter((m) => m.ns === pick)} go={(ns) => openNs(ns)} />
        ) : (
          <p className="f-note">Click a bubble to open it or see its vars; click an arrow for the functions it uses.</p>
        )}
      </div>
    </div>
  );
}

function EdgePanel({ edge, at, used, go }: { edge: { from: Node; to: Node }; at: string; used: Used[] | null; go: (ns: string, v: string) => void }) {
  const byNs = new Map<string, Used[]>();
  for (const u of used ?? []) byNs.set(u.ns, [...(byNs.get(u.ns) ?? []), u]);
  return (
    <>
      <h4 className="cl-title">{edge.from.key} <span className="cl-arrow">→</span> {edge.to.key}</h4>
      {!used ? <p className="f-note">Reading…</p> : !used.length ? <p className="f-note">No function calls cross it (the requires are for macros, specs or loading only).</p> : (
        <>
          <p className="f-note">{used.length} function{used.length > 1 ? "s" : ""} used, from {new Set(used.flatMap((u) => u.from)).size} namespace{new Set(used.flatMap((u) => u.from)).size > 1 ? "s" : ""}</p>
          {[...byNs].map(([ns, us]) => (
            <div key={ns} className="cl-group">
              <div className="cl-ns">{ns}</div>
              {us.map((u) => (
                <div key={u.name} className="cl-use">
                  <button type="button" className="cl-fn" onClick={() => go(u.ns, u.name)}>{u.name}</button>
                  <span className="cl-times">×{u.count}</span>
                  <span className="cl-from">← {u.from.map((f) => strip(f, edge.from.exact ? parent(edge.from.key) : edge.from.key) || f).join(", ")}</span>
                </div>
              ))}
            </div>
          ))}
        </>
      )}
    </>
  );
}

function NsPanel({ ns, detail, all, varPick, setVarPick, src, marks, go }: { ns: string; detail: Detail | null; all: Ns[]; varPick: string | null; setVarPick: (v: string | null) => void; src: Source | null; marks: any[]; go: (ns: string) => void }) {
  const me = all.find((n) => n.name === ns);
  const by = all.filter((n) => n.requires.includes(ns)).map((n) => n.name);
  return (
    <>
      <h4 className="cl-title">{ns}</h4>
      {detail?.doc && <p className="cl-doc">{detail.doc}</p>}
      <div className="cl-reqs">
        <span className="k">requires</span>
        {(me?.requires ?? []).map((r) => <button type="button" key={r} className="cl-chip" onClick={() => go(r)}>{r}</button>)}
        {(detail?.external ?? []).map((r) => <span key={r} className="cl-chip ext">{r}</span>)}
        {!me?.requires.length && !detail?.external.length && <span className="f-note">nothing</span>}
      </div>
      <div className="cl-reqs">
        <span className="k">required by</span>
        {by.map((r) => <button type="button" key={r} className="cl-chip" onClick={() => go(r)}>{r}</button>)}
        {!by.length && <span className="f-note">nobody</span>}
      </div>
      {!detail ? <p className="f-note">Reading…</p> : detail.vars.map((v) => {
        const m = marks.find((x) => x.var === v.name);
        const open = varPick === v.name;
        return (
          <div key={v.name} className={`cl-var${v.private ? " priv" : ""}${open ? " open" : ""}`}>
            <button type="button" className="cl-var-head" onClick={() => setVarPick(open ? null : v.name)}>
              <span className="cl-fn">{v.name}</span>
              {v.kind !== "fn" && <span className="cl-kind">{v.kind}</span>}
              {v.private && <span className="cl-kind">private</span>}
              {v.args.map((a, i) => <span key={i} className="cl-args">{a}</span>)}
            </button>
            {v.doc && <div className="cl-vdoc">{open ? v.doc : v.doc.split("\n")[0]}</div>}
            {m && <div className="cl-mark" style={{ color: MARK[m.color] ?? "var(--amber)" }}>● {m.note}</div>}
            {open && (src ? <Code src={src} /> : <p className="f-note">Reading…</p>)}
          </div>
        );
      })}
      {marks.filter((m) => !m.var).map((m) => <div key={m.id} className="cl-mark" style={{ color: MARK[m.color] ?? "var(--amber)" }}>● {m.note}</div>)}
    </>
  );
}
