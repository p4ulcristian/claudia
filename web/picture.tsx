// The right half: Claude's picture, a canvas of blocks: graphs laid out by ELK and animated
// between turns, and Claude's own HTML pages.
import React, { useEffect, useId, useMemo, useRef, useState } from "react";
import ELK from "elkjs/lib/elk.bundled.js";
import { md, highlight } from "./md";
import { FormView } from "./forms";
import { FORMS } from "../src/forms";
import type { Block, Scene, SceneNode, SceneEdge, SceneCard, SceneGroup } from "./types";

// What a graph is drawn from: a graph block, or an older picture's own nodes.
type Graph = { nodes: SceneNode[]; edges: SceneEdge[]; groups: SceneGroup[] };

const elk = new ELK();
const COLORS = ["green", "red", "amber", "blue", "grey", "violet"];
const FONT = '700 15px Kalam, cursive';
const GROUP_FONT = '700 14px Kalam, cursive';
const NOTE_FONT = '400 12px "Atkinson Hyperlegible", system-ui, sans-serif';

const canvas = document.createElement("canvas").getContext("2d")!;
function textW(s: string, font = FONT) {
  canvas.font = font;
  return canvas.measureText(s).width;
}

function wrap(s: string, max: number): string[] {
  const words = s.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let cur = "";
  for (const w of words) {
    if (cur && (cur + " " + w).length > max) { lines.push(cur); cur = w; }
    else cur = cur ? cur + " " + w : w;
  }
  if (cur) lines.push(cur);
  return lines.slice(0, 3);
}

type LaidNode = { id: string; x: number; y: number; w: number; h: number; d: number; lines: string[]; notes: string[]; node: SceneNode };
type LaidGroup = { id: string; x: number; y: number; w: number; h: number; group: SceneGroup };
type LaidEdge = { id: string; d: string; edge: SceneEdge; label?: { x: number; y: number; w: number; h: number } };
type Laid = { w: number; h: number; nodes: LaidNode[]; groups: LaidGroup[]; edges: LaidEdge[] };

// Labels and notes wrap, so one long note can't make a node (and the picture) wide.
const NOTE_LINE = 15;
function size(n: SceneNode) {
  const shape = n.shape ?? "circle";
  const lines = wrap(n.label || n.id, shape === "circle" ? 14 : 18);
  const notes = n.note ? wrap(n.note, 24).slice(0, 2) : [];
  const tw = Math.max(...lines.map((l) => textW(l)), 0);
  const nw = Math.max(...notes.map((l) => textW(l, NOTE_FONT)), 0);
  if (shape === "circle") {
    const d = Math.max(60, tw + 26, lines.length * 16 + 26);
    return { lines, notes, d, w: Math.max(d, notes.length ? nw + 8 : 0), h: d + (notes.length ? 8 + notes.length * NOTE_LINE : 0) };
  }
  let w = Math.max(96, tw + 32, nw + 28);
  let h = 22 + lines.length * 17 + notes.length * NOTE_LINE;
  if (shape === "diamond") { w *= 1.45; h *= 1.7; }
  return { lines, notes, d: 0, w, h };
}

function roundPath(pts: { x: number; y: number }[], r = 9) {
  if (pts.length < 2) return "";
  let d = `M${pts[0].x},${pts[0].y}`;
  for (let i = 1; i < pts.length - 1; i++) {
    const p = pts[i - 1], c = pts[i], n = pts[i + 1];
    const d1 = Math.hypot(c.x - p.x, c.y - p.y), d2 = Math.hypot(n.x - c.x, n.y - c.y);
    const k = Math.min(r, d1 / 2, d2 / 2);
    const a = { x: c.x + ((p.x - c.x) / d1) * k, y: c.y + ((p.y - c.y) / d1) * k };
    const b = { x: c.x + ((n.x - c.x) / d2) * k, y: c.y + ((n.y - c.y) / d2) * k };
    d += ` L${a.x},${a.y} Q${c.x},${c.y} ${b.x},${b.y}`;
  }
  const last = pts[pts.length - 1];
  return d + ` L${last.x},${last.y}`;
}

const eid = (e: SceneEdge) => `${e.from}->${e.to}`;

type Variant = "RIGHT" | "DOWN" | "DOWN-STACKED";

// Hidden arrows between a group's unconnected members, so top-to-bottom layouts
// stack them in a column instead of one wide row.
function stackEdges(scene: Graph) {
  const linked = new Set(scene.edges.flatMap((e) => [`${e.from}|${e.to}`, `${e.to}|${e.from}`]));
  const out: { id: string; sources: string[]; targets: string[] }[] = [];
  for (const g of scene.groups) {
    const members = scene.nodes.filter((n) => n.group === g.id);
    for (let i = 1; i < members.length; i++) {
      const a = members[i - 1].id, b = members[i].id;
      if (!linked.has(`${a}|${b}`)) out.push({ id: `~${a}~${b}`, sources: [a], targets: [b] });
    }
  }
  return out;
}

const minSize = (w: number, h: number, direction: string) => (direction === "DOWN" ? `(${h}, ${w})` : `(${w}, ${h})`);

async function layout(scene: Graph, variant: Variant): Promise<Laid> {
  const direction = variant === "RIGHT" ? "RIGHT" : "DOWN";
  const sizes = new Map(scene.nodes.map((n) => [n.id, size(n)]));
  const groupIds = new Set(scene.groups.map((g) => g.id));
  const leaf = (n: SceneNode) => ({ id: n.id, width: sizes.get(n.id)!.w, height: sizes.get(n.id)!.h });
  const children: any[] = [
    ...scene.groups.map((g) => ({
      id: g.id,
      layoutOptions: {
        "elk.padding": "[top=36,left=18,bottom=18,right=18]",
        // The frame is at least as wide as its title. ELK swaps the minimum's width and height in DOWN layouts.
        "elk.nodeSize.constraints": "MINIMUM_SIZE",
        "elk.nodeSize.minimum": minSize(Math.ceil(textW(g.label, GROUP_FONT) + 34), 60, direction),
      },
      children: scene.nodes.filter((n) => n.group === g.id).map(leaf),
    })).filter((g) => g.children.length),
    ...scene.nodes.filter((n) => !n.group || !groupIds.has(n.group)).map(leaf),
  ];
  const graph: any = await elk.layout({
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
      "elk.hierarchyHandling": "INCLUDE_CHILDREN",
      "elk.edgeRouting": "ORTHOGONAL",
      "elk.layered.spacing.nodeNodeBetweenLayers": "48",
      "elk.spacing.nodeNode": "22",
      "elk.spacing.edgeLabel": "4",
      "elk.edgeLabels.inline": "true",
      "elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      "elk.padding": "[top=12,left=12,bottom=12,right=12]",
    },
    children,
    edges: [
      ...scene.edges.map((e) => ({
        id: eid(e),
        sources: [e.from],
        targets: [e.to],
        labels: e.label ? [{ text: e.label, width: textW(e.label, NOTE_FONT) + 12, height: 18 }] : [],
      })),
      ...(variant === "DOWN-STACKED" ? stackEdges(scene) : []),
    ],
  });

  const offset = new Map<string, { x: number; y: number }>([["root", { x: 0, y: 0 }]]);
  const out: Laid = { w: graph.width ?? 0, h: graph.height ?? 0, nodes: [], groups: [], edges: [] };
  const byId = new Map(scene.nodes.map((n) => [n.id, n]));
  const groupById = new Map(scene.groups.map((g) => [g.id, g]));
  for (const c of graph.children ?? []) {
    if (groupById.has(c.id)) {
      offset.set(c.id, { x: c.x!, y: c.y! });
      out.groups.push({ id: c.id, x: c.x!, y: c.y!, w: c.width!, h: c.height!, group: groupById.get(c.id)! });
      for (const k of c.children ?? []) {
        const s = sizes.get(k.id)!;
        out.nodes.push({ id: k.id, x: c.x! + k.x!, y: c.y! + k.y!, w: k.width!, h: k.height!, d: s.d, lines: s.lines, notes: s.notes, node: byId.get(k.id)! });
      }
    } else {
      const s = sizes.get(c.id)!;
      out.nodes.push({ id: c.id, x: c.x!, y: c.y!, w: c.width!, h: c.height!, d: s.d, lines: s.lines, notes: s.notes, node: byId.get(c.id)! });
    }
  }
  const edgeById = new Map(scene.edges.map((e) => [eid(e), e]));
  for (const e of (graph.edges ?? []) as any[]) {
    if (!edgeById.has(e.id)) continue; // a hidden stacking arrow
    const o = offset.get(e.container ?? "root") ?? { x: 0, y: 0 };
    const sec = e.sections?.[0];
    if (!sec) continue;
    const pts = [sec.startPoint, ...(sec.bendPoints ?? []), sec.endPoint].map((p: any) => ({ x: p.x + o.x, y: p.y + o.y }));
    const lab = e.labels?.[0];
    out.edges.push({
      id: e.id,
      d: roundPath(pts),
      edge: edgeById.get(e.id)!,
      label: lab ? { x: lab.x + o.x, y: lab.y + o.y, w: lab.width, h: lab.height } : undefined,
    });
  }
  return out;
}

function NodeShape({ n, pulse, fresh }: { n: LaidNode; pulse: number; fresh: boolean }) {
  const { node } = n;
  const shape = node.shape ?? "circle";
  const color = node.color ?? "blue";
  const cls = `node c-${color} s-${node.state ?? "none"} ${fresh ? "fresh" : ""}`;
  const isCircle = shape === "circle";
  const cx = n.w / 2;
  const cy = isCircle ? n.d / 2 : n.h / 2;
  const textTop = cy - ((n.lines.length - 1) * 16) / 2 - (!isCircle ? (n.notes.length * NOTE_LINE) / 2 : 0);
  let outline: React.ReactNode;
  if (isCircle) outline = <circle className="body" cx={cx} cy={cy} r={n.d / 2} />;
  else if (shape === "diamond") outline = <polygon className="body" points={`${cx},1 ${n.w - 1},${cy} ${cx},${n.h - 1} 1,${cy}`} />;
  else outline = <rect className="body" x={0.75} y={0.75} width={n.w - 1.5} height={n.h - 1.5} rx={shape === "pill" ? n.h / 2 : 9} />;
  const ring = isCircle ? (
    <circle key={pulse} className="ring" cx={cx} cy={cy} r={n.d / 2 + 5} />
  ) : (
    <rect key={pulse} className="ring" x={-5} y={-5} width={n.w + 10} height={n.h + 10} rx={shape === "pill" ? n.h / 2 + 5 : 13} />
  );
  const badgeX = isCircle ? cx + n.d * 0.36 : n.w - 4;
  const badgeY = isCircle ? cy - n.d * 0.36 : 4;
  return (
    <g className={cls} style={{ transform: `translate(${n.x}px, ${n.y}px)` }}>
      {pulse > 0 && ring}
      {outline}
      <text className="label" x={cx} y={textTop} textAnchor="middle" dominantBaseline="middle">
        {n.lines.map((l, i) => (
          <tspan key={i} x={cx} dy={i ? 16 : 0}>{l}</tspan>
        ))}
      </text>
      {n.notes.length > 0 && (
        <text className="note" x={cx} y={isCircle ? n.d + 15 : textTop + (n.lines.length - 1) * 16 + 17} textAnchor="middle" dominantBaseline="middle">
          {n.notes.map((l, i) => <tspan key={i} x={cx} dy={i ? NOTE_LINE : 0}>{l}</tspan>)}
        </text>
      )}
      {node.state === "done" && (
        <g className="badge done" transform={`translate(${badgeX},${badgeY})`}>
          <circle r={8} /><path d="M-3.5,0 L-1,2.6 L3.8,-2.6" />
        </g>
      )}
      {node.state === "blocked" && (
        <g className="badge blocked" transform={`translate(${badgeX},${badgeY})`}>
          <circle r={8} /><path d="M-3,-3 L3,3 M3,-3 L-3,3" />
        </g>
      )}
    </g>
  );
}

const PAD = 14;
function availSize(el: HTMLElement | null) {
  const stage = el?.closest(".right-scroll") as HTMLElement | null;
  const w = (el?.clientWidth || (stage?.clientWidth ?? 800) - 36);
  return { w, h: Math.max(320, (stage?.clientHeight ?? 700) * 0.8) };
}
// Fit the width; the right side scrolls, so the picture may be up to twice the visible height.
const fit = (l: Laid, a: { w: number; h: number }) => Math.min(a.w / (l.w + PAD * 2), (a.h * 2) / (l.h + PAD * 2));

const VARIANTS: Variant[] = ["RIGHT", "DOWN", "DOWN-STACKED"];

export function Diagram({ scene, changed, seq }: { scene: Graph; changed: string[]; seq: number }) {
  // Each graph has its own chalk filter and arrowheads: the filter's region is sized to its graph.
  const uid = useId().replace(/:/g, "");
  const [laid, setLaid] = useState<Laid | null>(null);
  const [view, setView] = useState({ k: 1, x: 0, y: 0 });
  const [scale, setScale] = useState(1);
  const known = useRef(new Set<string>());
  const fresh = useRef(new Set<string>());
  const run = useRef(0);
  const box = useRef<HTMLDivElement>(null);
  const layer = useRef<HTMLDivElement>(null);

  // Zoom around a point of the diagram box (px from its top-left).
  const zoomAt = (px: number, py: number, factor: number) =>
    setView((v) => {
      const k = Math.min(4, Math.max(0.3, v.k * factor));
      const lx = layer.current?.offsetLeft ?? 0, ly = layer.current?.offsetTop ?? 0;
      const ux = (px - lx - v.x) / v.k, uy = (py - ly - v.y) / v.k;
      return { k, x: px - lx - ux * k, y: py - ly - uy * k };
    });

  // Ctrl+wheel and trackpad pinch zoom the picture, not the page. React's wheel
  // listener is passive and can't stop the browser zoom, so this one is native.
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      const r = el.getBoundingClientRect();
      zoomAt(e.clientX - r.left, e.clientY - r.top, Math.exp(-e.deltaY * 0.0025));
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  }, [laid]);

  const dir = useRef<Variant | null>(null);
  const [width, setWidth] = useState(0);

  // Lay out again when the block changes width noticeably (window resize, divider drag).
  useEffect(() => {
    const stage = box.current;
    if (!stage) return;
    let t: Timer | undefined;
    const ro = new ResizeObserver(([e]) => {
      clearTimeout(t);
      t = setTimeout(() => setWidth((w) => (Math.abs(e.contentRect.width - w) > 40 ? e.contentRect.width : w)), 150);
    });
    ro.observe(stage);
    return () => { ro.disconnect(); clearTimeout(t); };
  }, []);

  useEffect(() => {
    const mine = ++run.current;
    if (!scene.nodes.length) { setLaid(null); dir.current = null; return; }
    const avail = availSize(box.current);
    document.fonts.ready
      .then(() => Promise.all(VARIANTS.map((v) => layout(scene, v))))
      .then((all) => {
        if (mine !== run.current) return;
        // Pick the layout that fits the pane best; switch only when clearly better, so the picture doesn't jump.
        const fits = all.map((l) => fit(l, avail));
        let best = fits.indexOf(Math.max(...fits));
        const kept = dir.current ? VARIANTS.indexOf(dir.current) : -1;
        if (kept >= 0 && fits[best] < 1.25 * fits[kept]) best = kept;
        dir.current = VARIANTS[best];
        const l = all[best];
        fresh.current = new Set([...l.nodes.map((n) => n.id), ...l.edges.map((e) => e.id)].filter((id) => !known.current.has(id)));
        known.current = new Set([...l.nodes.map((n) => n.id), ...l.edges.map((e) => e.id)]);
        setScale(Math.max(0.5, Math.min(1.2, fit(l, avail))));
        setLaid(l);
      })
      .catch((e) => console.error("layout", e));
  }, [scene, width]);

  if (!laid) return <div className="diagram" ref={box} />;
  const changedSet = new Set(changed);
  const pad = PAD;
  const onDown = (e: React.PointerEvent) => {
    const start = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y };
    const move = (m: PointerEvent) => setView((v) => ({ ...v, x: start.vx + m.clientX - start.x, y: start.vy + m.clientY - start.y }));
    const up = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };
  return (
    <div className="diagram" ref={box} onPointerDown={onDown} onDoubleClick={() => setView({ k: 1, x: 0, y: 0 })} style={{ "--chalk-url": `url(#chalk-${uid})` } as React.CSSProperties}>
      <div className="zoom-layer" ref={layer} style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.k})` }}>
      <svg viewBox={`${-pad} ${-pad} ${laid.w + pad * 2} ${laid.h + pad * 2}`} width={(laid.w + pad * 2) * scale} height={(laid.h + pad * 2) * scale}>
        <defs>
          {/* Chalk: lines wobble a little, like they were drawn by hand. One region covers the whole picture. */}
          <filter id={`chalk-${uid}`} filterUnits="userSpaceOnUse" x={-pad - 40} y={-pad - 40} width={laid.w + pad * 2 + 80} height={laid.h + pad * 2 + 80}>
            <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" seed="7" result="noise" />
            <feDisplacementMap in="SourceGraphic" in2="noise" scale="2.4" xChannelSelector="R" yChannelSelector="G" />
          </filter>
          {COLORS.map((c) => (
            <marker key={c} id={`arrow-${uid}-${c}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" className={`arrowhead c-${c}`} />
            </marker>
          ))}
        </defs>
        <g>
          {laid.groups.map((g) => (
            <g key={g.id} className={`group c-${g.group.color ?? "grey"}`} style={{ transform: `translate(${g.x}px, ${g.y}px)` }}>
              <rect width={g.w} height={g.h} rx={13} />
              <text x={16} y={22}>{g.group.label}</text>
            </g>
          ))}
          {laid.edges.map((e) => {
            const c = e.edge.color ?? "grey";
            const isNew = fresh.current.has(e.id);
            return (
              <g key={e.id} className={`edge c-${c} ${isNew ? "fresh" : ""} ${changedSet.has(e.id) ? "hot" : ""}`}>
                <path style={{ d: `path("${e.d}")` } as any} strokeDasharray={e.edge.dashed ? "5 4" : undefined} markerEnd={`url(#arrow-${uid}-${c})`} />
                {e.label && e.edge.label && (
                  <g className="edge-label" style={{ transform: `translate(${e.label.x}px, ${e.label.y}px)` }}>
                    <rect width={e.label.w} height={e.label.h} rx={5} />
                    <text x={e.label.w / 2} y={e.label.h / 2 + 0.5} textAnchor="middle" dominantBaseline="middle">{e.edge.label}</text>
                  </g>
                )}
              </g>
            );
          })}
          {laid.nodes.map((n) => (
            <NodeShape key={n.id} n={n} fresh={fresh.current.has(n.id)} pulse={changedSet.has(n.id) && !fresh.current.has(n.id) ? seq : 0} />
          ))}
        </g>
      </svg>
      </div>
    </div>
  );
}

// What a page card's frame gets from Claudia: the theme as CSS variables, the fonts, a fitted height.
const THEME_VARS = ["--bg", "--bg2", "--panel", "--panel2", "--fg", "--muted", "--line", "--accent", "--green", "--red", "--amber", "--blue", "--grey", "--violet",
  "--green-bg", "--red-bg", "--amber-bg", "--blue-bg", "--grey-bg", "--violet-bg", "--sans", "--mono", "--hand"];
const FONTS = "https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400&family=Kalam:wght@400;700&family=JetBrains+Mono:wght@400;500&display=swap";
// connect-src: only what the snapshot script fetches to embed the fonts in its image.
const PAGE_CSP = "default-src 'none'; script-src 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; font-src https://fonts.gstatic.com data:; img-src data: blob: https:; connect-src https://fonts.googleapis.com https://fonts.gstatic.com";
// The same html-to-image the page bundles, for frames to draw themselves when the picture is saved.
const SNAP_LIB = "https://cdn.jsdelivr.net/npm/html-to-image@1.11.13/dist/html-to-image.js";

function pageDoc(html: string) {
  const cs = getComputedStyle(document.documentElement);
  const vars = THEME_VARS.map((v) => `${v}: ${cs.getPropertyValue(v).trim()};`).join(" ");
  const head = `<meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${PAGE_CSP}"><link rel="stylesheet" href="${FONTS}">`
    + `<style>:root { ${vars} color-scheme: dark; } html, body { margin: 0; background: transparent; color: var(--fg); font: 14px/1.5 var(--sans); } *, *::before, *::after { box-sizing: border-box; } button, input, select, textarea { font: inherit; color: inherit; }</style>`
    + `<script>(() => { const post = () => parent.postMessage({ claudiaPageHeight: Math.ceil(document.body.scrollHeight) }, "*"); addEventListener("load", post); addEventListener("DOMContentLoaded", () => new ResizeObserver(post).observe(document.body)); })();</script>`
    // Parts: Claudia sends new markup for the elements marked data-part="<name>"; the page stays loaded.
    + `<script>(() => { const put = (parts) => { for (const [k, v] of Object.entries(parts || {})) for (const el of document.querySelectorAll('[data-part="' + CSS.escape(k) + '"]')) el.innerHTML = v; }; let first = null; addEventListener("message", (e) => { if (e.source !== parent || !e.data || !e.data.claudiaParts) return; if (document.readyState === "loading") first = e.data.claudiaParts; else put(e.data.claudiaParts); }); addEventListener("DOMContentLoaded", () => { put(first); parent.postMessage({ claudiaReady: true }, "*"); }); })();</script>`
    // Snapshot: on Claudia's ask, draw this page into a PNG and hand it back (see web/snapshot.ts).
    + `<script>(() => { let lib; const load = () => lib || (lib = new Promise((ok, no) => { const s = document.createElement("script"); s.src = "${SNAP_LIB}"; s.onload = () => ok(window.htmlToImage); s.onerror = no; document.head.appendChild(s); })); addEventListener("message", async (e) => { if (e.source !== parent || !e.data || !e.data.claudiaSnap) return; let png = null; try { png = await (await load()).toPng(document.documentElement, { pixelRatio: e.data.ratio || 2, width: document.documentElement.clientWidth, height: Math.max(document.documentElement.scrollHeight, innerHeight) }); } catch {} parent.postMessage({ claudiaSnapId: e.data.claudiaSnap, png }, "*"); }); })();</script>`;
  if (/<head[^>]*>/i.test(html)) return html.replace(/<head[^>]*>/i, (m) => m + head);
  if (/<html[^>]*>/i.test(html)) return html.replace(/<html[^>]*>/i, (m) => `${m}<head>${head}</head>`);
  return `<!doctype html><html><head>${head}</head><body>${html}</body></html>`;
}

// A page Claude wrote, sandboxed: its scripts run, but it has no access to Claudia, its cookies or its API.
export function PageFrame({ html, height, parts }: { html: string; height?: number; parts?: Record<string, string> }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [fit, setFit] = useState(240);
  const doc = useMemo(() => pageDoc(html), [html]);
  const partsRef = useRef(parts);
  partsRef.current = parts;
  const send = () => partsRef.current && frame.current?.contentWindow?.postMessage({ claudiaParts: partsRef.current }, "*");
  useEffect(() => {
    const on = (e: MessageEvent) => {
      if (e.source !== frame.current?.contentWindow) return;
      if (e.data?.claudiaReady) send();
      const h = Number(e.data?.claudiaPageHeight);
      if (h > 0) setFit(Math.min(2400, Math.max(60, h)));
    };
    window.addEventListener("message", on);
    return () => window.removeEventListener("message", on);
  }, []);
  // A part changed: swap it in place (a fresh page gets them when it says it's ready).
  useEffect(() => { send(); }, [parts]);
  return <iframe ref={frame} className="page-frame" sandbox="allow-scripts" srcDoc={doc} style={{ height: height ?? fit }} title="Page" />;
}

function Card({ card, hot, seq }: { card: SceneCard; hot: boolean; seq: number }) {
  const html = useMemo(() => {
    if (card.kind === "code") return highlight(card.code ?? card.text ?? "", card.lang);
    if (card.kind === "text") return md(card.text ?? "");
    return "";
  }, [card]);
  return (
    <section className={`card c-${card.color ?? "grey"} k-${card.kind ?? "text"} ${hot ? "hot" : ""}`}>
      {card.title && <h3>{card.title}</h3>}
      {card.kind === "text" && <div className="md" dangerouslySetInnerHTML={{ __html: html }} />}
      {card.kind === "code" && (
        <div className="code">
          <button className="copy" type="button" title="Copy">Copy</button>
          <pre><code className="hljs" dangerouslySetInnerHTML={{ __html: html }} /></pre>
        </div>
      )}
      {card.kind === "list" && (
        <ul>{(card.items ?? []).map((it, i) => <li key={i} dangerouslySetInnerHTML={{ __html: md(it) }} />)}</ul>
      )}
      {card.kind === "page" && <PageFrame html={card.html ?? ""} height={card.height} />}
      {card.kind === "table" && (
        <div className="table-wrap">
          <table>
            {card.columns?.length ? <thead><tr>{card.columns.map((c, i) => <th key={i}>{c}</th>)}</tr></thead> : null}
            <tbody>{(card.rows ?? []).map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} dangerouslySetInnerHTML={{ __html: md(c) }} />)}</tr>)}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// The badge over a block: its kind (graph, a form's name, freeform or template) and its name.
export function BlockBadge({ block }: { block: Block }) {
  const kind = block.kind === "graph" ? "graph" : block.kind === "form" ? block.form : block.template ? "template" : "freeform";
  const label = block.kind === "form" ? (FORMS[block.form]?.label ?? block.form).toLowerCase() : kind;
  const name = block.name ?? (block.kind === "html" ? block.template : undefined);
  return (
    <span className={`block-badge b-${kind}`} title={block.kind === "form" ? FORMS[block.form]?.when : undefined}>
      <b>{label}</b>
      {name && <span className="block-name">{name}</span>}
    </span>
  );
}

// Keep a freeform block as a named template (Claude can draw it again and change it by name).
function SaveTemplate({ chat, block }: { chat: string; block: Block }) {
  const [name, setName] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => {
    if (!note) return;
    const t = setTimeout(() => setNote(null), 3500);
    return () => clearTimeout(t);
  }, [note]);
  const save = async () => {
    const n = (name ?? "").trim();
    if (!n) return setName(null);
    const r = await fetch(`/api/chats/${chat}/blocks/${encodeURIComponent(block.id)}/template`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: n }),
    }).then((x) => x.json()).catch(() => ({ text: "Couldn't save the template." }));
    setName(null);
    setNote(r.text);
  };
  if (note) return <span className="tpl-note">{note}</span>;
  if (name === null)
    return <button className="tpl-save" onClick={() => setName(block.name ?? (block.kind === "html" ? block.template : undefined) ?? block.id)} title="Save as a template Claude can reuse and change by name">save as template</button>;
  return (
    <span className="tpl-form">
      <input
        autoFocus
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => { if (e.key === "Enter") save(); if (e.key === "Escape") setName(null); }}
        placeholder="Template name"
      />
      <button onClick={save}>save</button>
    </span>
  );
}

function BlockView({ block, changed, seq, chat, browse }: { block: Block; changed: Set<string>; seq: number; chat?: string; browse?: string }) {
  const hot = changed.has(block.id) || [...changed].some((c) => c.startsWith(`${block.id}:`));
  return (
    <section className={`block w-${block.width ?? "full"} k-${block.kind}`}>
      {/* A new glow per change; the block itself stays mounted (an HTML block keeps its page loaded). */}
      {hot && <span key={seq} className="block-glow" />}
      <div className="block-head">
        <BlockBadge block={block} />
        {chat && block.kind === "html" && <SaveTemplate chat={chat} block={block} />}
      </div>
      {block.kind === "graph" ? <Diagram scene={block} changed={[...changed]} seq={seq} />
        : block.kind === "form" ? <FormView form={block.form} data={block.data} chat={browse} />
        : <PageFrame html={block.html} height={block.height} parts={block.parts} />}
    </section>
  );
}

// chat: where the shown picture is live (it can save templates); left out for older pictures.
// browse: the chat the picture belongs to, always (forms read its files through it, old pictures too).
export function PictureView({ scene, changed, seq, chat, browse }: { scene: Scene; changed: string[]; seq: number; chat?: string; browse?: string }) {
  const hot = new Set(changed);
  const blocks = scene.blocks;
  if (blocks ? !blocks.length : !scene.nodes.length && !scene.cards.length)
    return (
      <div className="picture-empty">
        <div className="dots"><span /><span /><span /></div>
        <p>Claude draws here as it works.</p>
      </div>
    );
  // No title on the board: the title names the picture only in the scrubber under it.
  if (blocks)
    return (
      <div className="picture blocks">
        {blocks.map((b) => <BlockView key={b.id} block={b} changed={hot} seq={seq} chat={chat} browse={browse} />)}
      </div>
    );
  // A picture from before blocks: its graph and cards as they were drawn.
  return (
    <div className="picture">
      {scene.nodes.length > 0 && <Diagram scene={scene} changed={changed} seq={seq} />}
      {scene.cards.length > 0 && (
        <div className="cards">
          {scene.cards.map((c) => <Card key={hot.has(c.id) ? `${c.id}:${seq}` : c.id} card={c} hot={hot.has(c.id)} seq={seq} />)}
        </div>
      )}
    </div>
  );
}
