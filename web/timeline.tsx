// The timeline on the right: the chat and every subagent as lanes on one time axis. Each tool call is
// a bar as long as it ran; messages are dots. Quiet stretches are folded into a narrow gap with their
// length on it. Clicking a bar or dot opens it in full below.
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { md } from "./md";
import type { Timeline, TimelineDetail, TimelineEvent, TimelineLane } from "./types";

const LABEL = 240; // lane names column (.tl-label width)
const ROW = 30;
const PAD = 16;
const GAP_PX = 38; // a folded quiet stretch
const IDLE = 60_000; // longer than this with nothing happening gets folded
const MIN_SCALE = 0.02; // px per second
const MAX_SCALE = 400;
const AUTO_MIN = 0.8; // the starting zoom never goes below this; longer chats scroll
const STEPS = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600, 43200, 86400];

type Seg = { start: number; end: number; x: number };
type Sel = { lane: string; id: string };

function dur(ms: number) {
  const s = Math.round(ms / 1000);
  if (s < 60) return ms < 1000 ? `${(ms / 1000).toFixed(1)}s` : `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ${m % 60}m`;
  return `${Math.floor(h / 24)}d ${h % 24}h`;
}

const clock = (t: number, secs: boolean) =>
  new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", ...(secs ? { second: "2-digit" } : {}), hour12: false });

// Where a tool call ends: its result, now if its lane still runs, or nowhere (it never got one).
const endOf = (e: Extract<TimelineEvent, { k: "tool" }>, lane: TimelineLane, now: number) => e.end ?? (lane.running ? now : undefined);

// Stretches with something going on: every step, and an agent's whole run (it thinks between steps).
function segments(t: Timeline): Omit<Seg, "x">[] {
  const iv: [number, number][] = [];
  for (const l of t.lanes) {
    if (l.kind !== "main") iv.push([l.start, l.end]);
    for (const e of l.events) {
      if (e.k === "tool") iv.push([e.start, endOf(e, l, t.now) ?? e.start]);
      else iv.push([e.at, e.at]);
    }
    if (l.running) iv.push([t.now, t.now]);
  }
  iv.sort((a, b) => a[0] - b[0]);
  const out: Omit<Seg, "x">[] = [];
  for (const [s, e] of iv) {
    const last = out[out.length - 1];
    if (last && s - last.end <= IDLE) last.end = Math.max(last.end, e);
    else out.push({ start: s, end: e });
  }
  return out;
}

function layout(raw: Omit<Seg, "x">[], scale: number) {
  let x = PAD;
  const segs: Seg[] = raw.map((s, i) => {
    if (i) x += GAP_PX;
    const seg = { ...s, x };
    x += ((s.end - s.start) / 1000) * scale;
    return seg;
  });
  const width = x + PAD;
  const xAt = (t: number) => {
    if (!segs.length) return PAD;
    for (let i = 0; i < segs.length; i++) {
      const s = segs[i];
      if (t > s.end) continue;
      if (t >= s.start || !i) return s.x + (Math.max(0, t - s.start) / 1000) * scale;
      const p = segs[i - 1];
      const prevEnd = p.x + ((p.end - p.start) / 1000) * scale;
      return prevEnd + (GAP_PX * (t - p.end)) / (s.start - p.end);
    }
    const l = segs[segs.length - 1];
    return l.x + ((l.end - l.start) / 1000) * scale;
  };
  const tAt = (x: number) => {
    for (let i = 0; i < segs.length; i++) {
      const s = segs[i];
      const sEnd = s.x + ((s.end - s.start) / 1000) * scale;
      if (x <= sEnd) {
        if (x >= s.x || !i) return s.start + (Math.max(0, x - s.x) / scale) * 1000;
        const p = segs[i - 1];
        return p.end + ((s.start - p.end) * (x - (s.x - GAP_PX))) / GAP_PX;
      }
    }
    return segs.length ? segs[segs.length - 1].end : 0;
  };
  return { segs, width, xAt, tAt };
}

function category(name: string) {
  if (/^(Read|Grep|Glob|LS|ToolSearch|NotebookRead)$/.test(name)) return "read";
  if (/^(Edit|Write|NotebookEdit|MultiEdit)$/.test(name)) return "edit";
  if (/^(Bash|Monitor|TaskStop|PowerShell)$/.test(name)) return "shell";
  if (/^(WebFetch|WebSearch)$/.test(name)) return "web";
  if (/^(Agent|Task|SendMessage|Workflow)$/.test(name)) return "agent";
  return "other";
}

const LEGEND = [["read", "read"], ["edit", "edit"], ["shell", "shell"], ["web", "web"], ["agent", "agent"], ["other", "other"]] as const;

export function TimelineView({ chat, live }: { chat: string; live: boolean }) {
  const [data, setData] = useState<Timeline | null>(null);
  const [missing, setMissing] = useState(false);
  const [zoom, setZoom] = useState<number | null>(null); // null: the starting zoom, which follows the width
  const [sel, setSel] = useState<Sel | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [viewW, setViewW] = useState(800);
  const scroller = useRef<HTMLDivElement>(null);
  const v = useRef("");
  const atEnd = useRef(true);
  const anchor = useRef<{ t: number; px: number } | null>(null);

  useEffect(() => {
    setData(null);
    setMissing(false);
    setSel(null);
    setZoom(null);
    v.current = "";
    atEnd.current = true;
  }, [chat]);

  const load = useCallback(async () => {
    const r = await fetch(`/api/chats/${chat}/timeline?v=${encodeURIComponent(v.current)}`).catch(() => null);
    if (!r) return;
    if (r.status === 404) return setMissing(true);
    if (!r.ok) return;
    const j = await r.json();
    setMissing(false);
    if (j.same) return setData((d) => (d ? { ...d, now: j.now } : d));
    v.current = j.v;
    setData(j);
  }, [chat]);

  // Every 2 s while anything runs; once more when it stops.
  useEffect(() => {
    load();
    if (!live) return;
    const t = setInterval(load, 2000);
    return () => clearInterval(t);
  }, [load, live]);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setViewW(el.clientWidth));
    ro.observe(el);
    setViewW(el.clientWidth);
    return () => ro.disconnect();
  }, [data === null]);

  const raw = useMemo(() => (data ? segments(data) : []), [data]);
  const active = raw.reduce((n, s) => n + (s.end - s.start) / 1000, 0);
  const fit = Math.max(MIN_SCALE, Math.min(MAX_SCALE, (viewW - LABEL - 2 * PAD - Math.max(0, raw.length - 1) * GAP_PX) / Math.max(active, 1)));
  const scale = zoom ?? Math.max(fit, AUTO_MIN);
  const L = useMemo(() => layout(raw, scale), [raw, scale]);

  // Keep the newest part in view while following; keep the point under the mouse still when zooming.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    if (anchor.current) {
      el.scrollLeft = LABEL + L.xAt(anchor.current.t) - anchor.current.px;
      anchor.current = null;
    } else if (atEnd.current) el.scrollLeft = el.scrollWidth;
  }, [L]);

  const zoomTo = (next: number, px?: number) => {
    const el = scroller.current;
    if (!el) return;
    const at = px ?? el.clientWidth / 2;
    anchor.current = { t: L.tAt(el.scrollLeft + at - LABEL), px: at };
    setZoom(Math.max(MIN_SCALE, Math.min(MAX_SCALE, next)));
  };

  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      zoomTo(scale * (e.deltaY < 0 ? 1.25 : 0.8), e.clientX - el.getBoundingClientRect().left);
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  });

  useEffect(() => {
    const key = (e: KeyboardEvent) => e.key === "Escape" && sel && (e.stopPropagation(), setSel(null));
    window.addEventListener("keydown", key, true);
    return () => window.removeEventListener("keydown", key, true);
  }, [sel]);

  const depth = useMemo(() => {
    const byId = new Map(data?.lanes.map((l) => [l.id, l]) ?? []);
    const d = (l: TimelineLane): number => (l.parent && byId.has(l.parent) ? 1 + d(byId.get(l.parent)!) : 0);
    return new Map(data?.lanes.map((l) => [l.id, d(l)]) ?? []);
  }, [data]);

  const showLane = (id: string) => {
    const i = data?.lanes.findIndex((l) => l.id === id) ?? -1;
    const el = scroller.current;
    if (i < 0 || !el) return;
    const lane = data!.lanes[i];
    el.scrollTo({ top: Math.max(0, i * ROW - el.clientHeight / 3), left: LABEL + L.xAt(lane.start) - el.clientWidth / 3, behavior: "smooth" });
    atEnd.current = false;
    setFlash(id);
    setTimeout(() => setFlash((f) => (f === id ? null : f)), 1600);
  };

  if (missing) return <div className="tl"><p className="muted tl-empty">Nothing yet: the timeline fills in once Claude starts working in this chat.</p></div>;
  if (!data) return <div className="tl"><p className="muted tl-empty">Loading…</p></div>;

  const agents = data.lanes.filter((l) => l.kind !== "main");
  const running = agents.filter((l) => l.running).length;
  const step = (STEPS.find((s) => s * scale >= 90) ?? 86400) * 1000;
  const secs = step < 60_000;
  const selLane = sel && data.lanes.find((l) => l.id === sel.lane);
  const selEv = selLane?.events.find((e) => e.id === sel!.id);

  return (
    <div className="tl">
      <div className="tl-head">
        <b>Timeline</b>
        <span className="muted small">
          {agents.length ? `${agents.length} ${agents.length === 1 ? "agent" : "agents"}${running ? ` · ${running} running` : ""}` : "no agents yet"}
        </span>
        <span className="tl-legend">
          {LEGEND.map(([c, label]) => <span key={c} className={`tl-key ${c}`}>{label}</span>)}
        </span>
        <span className="tl-zoom">
          <button className="chev-btn" onClick={() => zoomTo(scale * 0.66)} title="Zoom out (Ctrl+wheel)">−</button>
          <button className={`tl-fit ${zoom === null ? "on" : ""}`} onClick={() => { atEnd.current = true; setZoom(null); }} title="Back to the starting zoom, following the newest">Fit</button>
          <button className="chev-btn" onClick={() => zoomTo(scale * 1.5)} title="Zoom in (Ctrl+wheel)">+</button>
        </span>
      </div>
      <div
        className="tl-scroll"
        ref={scroller}
        onScroll={(e) => {
          const el = e.currentTarget;
          atEnd.current = el.scrollLeft + el.clientWidth >= el.scrollWidth - 24;
        }}
      >
        <div className="tl-canvas" style={{ width: LABEL + L.width }}>
          <div className="tl-ruler" style={{ height: ROW }}>
            <div className="tl-label tl-corner" />
            <div className="tl-track">
              {L.segs.map((s, i) => (
                <React.Fragment key={s.start}>
                  {i > 0 && (
                    <span className="tl-gap-label" style={{ left: s.x - GAP_PX, width: GAP_PX }} title={`${dur(s.start - L.segs[i - 1].end)} with nothing going on`}>
                      {dur(s.start - L.segs[i - 1].end)}
                    </span>
                  )}
                  <span className="tl-tick start" style={{ left: s.x }}>{clock(s.start, secs)}</span>
                  {ticks(s, step).map((t) => {
                    const x = L.xAt(t);
                    return x - s.x < 70 ? null : <span key={t} className="tl-tick" style={{ left: x }}>{clock(t, secs)}</span>;
                  })}
                </React.Fragment>
              ))}
            </div>
          </div>
          {L.segs.slice(1).map((s) => (
            <div key={s.start} className="tl-gap" style={{ left: LABEL + s.x - GAP_PX, width: GAP_PX }} />
          ))}
          {data.lanes.map((lane) => (
            <div key={lane.id} className={`tl-row ${lane.running ? "running" : ""} ${flash === lane.id ? "flash" : ""}`} style={{ height: ROW }}>
              <div className="tl-label" style={{ paddingLeft: 10 + 14 * (depth.get(lane.id) ?? 0) }} title={`${lane.title}\n${lane.kind} · ${dur(lane.end - lane.start)}\nClick: go to its start`} onClick={() => showLane(lane.id)}>
                <span className="tl-dot" />
                <span className="tl-name">{lane.title}</span>
                {lane.kind !== "main" && <span className="tl-kind">{lane.kind}</span>}
              </div>
              <div className="tl-track">
                {lane.kind !== "main" && <span className="tl-span" style={{ left: L.xAt(lane.start), width: Math.max(1, L.xAt(lane.end) - L.xAt(lane.start)) }} />}
                {lane.events.map((e) => <Mark key={e.id} e={e} lane={lane} now={data.now} xAt={L.xAt} on={sel?.lane === lane.id && sel.id === e.id} onClick={() => setSel({ lane: lane.id, id: e.id })} />)}
              </div>
            </div>
          ))}
        </div>
      </div>
      {selLane && selEv && <DetailPane chat={chat} lane={selLane} e={selEv} now={data.now} onClose={() => setSel(null)} onLane={showLane} />}
    </div>
  );
}

function ticks(s: Seg, step: number) {
  const out: number[] = [];
  for (let t = Math.ceil(s.start / step) * step; t <= s.end && out.length < 500; t += step) out.push(t);
  return out;
}

function Mark({ e, lane, now, xAt, on, onClick }: { e: TimelineEvent; lane: TimelineLane; now: number; xAt: (t: number) => number; on: boolean; onClick: () => void }) {
  if (e.k !== "tool") {
    const tip = `${e.k === "user" ? "Message" : e.k === "note" ? "Note" : "Claude"} · ${clock(e.at, true)}\n${e.text}`;
    return <button className={`tl-point ${e.k} ${on ? "on" : ""}`} style={{ left: xAt(e.at) }} title={tip} onClick={onClick} />;
  }
  const end = endOf(e, lane, now);
  const left = xAt(e.start);
  const width = end === undefined ? 6 : Math.max(3, xAt(end) - left);
  const state = e.error ? "err" : e.end === undefined ? (lane.running ? "open" : "lost") : "";
  const tip = `${e.name} ${e.title}\n${clock(e.start, true)} · ${end === undefined ? "no result" : dur(end - e.start)}${e.end === undefined && lane.running ? " so far" : ""}${e.error ? " · failed" : ""}`;
  return (
    <button className={`tl-bar ${category(e.name)} ${state} ${on ? "on" : ""}`} style={{ left, width }} title={tip} onClick={onClick}>
      {width > 44 && <span>{e.name}{width > 110 && e.title ? ` ${e.title}` : ""}</span>}
    </button>
  );
}

function DetailPane({ chat, lane, e, now, onClose, onLane }: { chat: string; lane: TimelineLane; e: TimelineEvent; now: number; onClose: () => void; onLane: (id: string) => void }) {
  const [d, setD] = useState<TimelineDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const done = e.k === "tool" ? e.end : 0;
  useEffect(() => {
    let dead = false;
    setD(null);
    setFailed(false);
    fetch(`/api/chats/${chat}/timeline/${lane.id}/${encodeURIComponent(e.id)}`)
      .then((r) => (r.ok ? r.json() : Promise.reject()))
      .then((j) => !dead && setD(j), () => !dead && setFailed(true));
    return () => { dead = true; };
  }, [chat, lane.id, e.id, done]);

  const end = e.k === "tool" ? endOf(e, lane, now) : undefined;
  const when = e.k === "tool" ? e.start : e.at;
  const head =
    e.k === "tool" ? <><b>{e.name}</b> <span className="tl-d-title">{e.title}</span></>
      : <b>{e.k === "user" ? (lane.kind === "main" ? "Message" : "Its task") : e.k === "note" ? "Note" : "Claude"}</b>;
  return (
    <div className="tl-detail">
      <div className="tl-d-head">
        <span className="tl-d-lane muted small">{lane.title} ›</span>
        {head}
        <span className="muted small">
          {clock(when, true)}
          {e.k === "tool" && (end === undefined ? " · no result" : ` · ${dur(end - e.start)}${e.end === undefined ? " so far" : ""}`)}
        </span>
        {e.k === "tool" && e.error && <span className="chip red">failed</span>}
        {e.k === "tool" && e.agent && <button className="link" onClick={() => onLane(e.agent!)}>show its lane</button>}
        <button className="tl-close" onClick={onClose} title="Close (Esc)">×</button>
      </div>
      <div className="tl-d-body">
        {failed ? <p className="muted small">Couldn't read this from the session file.</p>
          : !d ? <p className="muted small">Loading…</p>
          : d.k === "text" ? <div className="md small" dangerouslySetInnerHTML={{ __html: md(d.text) }} />
          : (
            <>
              <h4>Input</h4>
              <pre>{d.input}</pre>
              <h4>Output</h4>
              {d.output === null ? <p className="muted small">{lane.running ? "Still running." : "It got no result (stopped or cut off)."}</p>
                : <pre className={d.error ? "err" : ""}>{d.output || "(no output)"}</pre>}
            </>
          )}
        {d?.truncated && <p className="muted small">Cut at 1 MB.</p>}
      </div>
    </div>
  );
}
