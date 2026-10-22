// The timeline on the right: every step of the chat and its subagents as one feed, top to bottom in
// time order. A thin rail per agent on the left shows who was running at the same time; quiet
// stretches get a divider with their length. Clicking a step opens it in full under it.
import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { md } from "./md";
import { PatchDiff, tilde } from "./changes";
import type { Timeline, TimelineDetail, TimelineEvent, TimelineLane } from "./types";

const RAIL = 14; // px per rail
const IDLE = 60_000; // a longer quiet stretch gets a divider
const PAGE = 400; // rows shown at first; earlier ones on request
const PALETTE = ["sky", "violet", "teal", "pink", "amber", "lime", "orange", "indigo", "cyan", "magenta", "green", "red", "yellow", "purple", "blue", "brown"];

type Row = { lane: TimelineLane; e: TimelineEvent; at: number };

function dur(ms: number) {
  const s = Math.round(ms / 1000);
  if (s < 60) return ms < 1000 ? `${(ms / 1000).toFixed(1)}s` : `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ${m % 60}m`;
  return `${Math.floor(h / 24)}d ${h % 24}h`;
}

const clock = (t: number) => new Date(t).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
const day = (t: number) => new Date(t).toLocaleDateString([], { month: "short", day: "numeric", weekday: "short" });

function category(name: string) {
  if (/^(Read|Grep|Glob|LS|ToolSearch|NotebookRead)$/.test(name)) return "read";
  if (/^(Edit|Write|NotebookEdit|MultiEdit)$/.test(name)) return "edit";
  if (/^(Bash|Monitor|TaskStop|PowerShell)$/.test(name)) return "shell";
  if (/^(WebFetch|WebSearch)$/.test(name)) return "web";
  if (/^(Agent|Task|SendMessage|Workflow)$/.test(name)) return "agent";
  return "other";
}

export function TimelineView({ chat, live }: { chat: string; live: boolean }) {
  const [data, setData] = useState<Timeline | null>(null);
  const [missing, setMissing] = useState(false);
  const [open, setOpen] = useState<string | null>(null); // lane:id of the step shown in full
  const [only, setOnly] = useState<string | null>(null); // one lane, with the agents it started
  const [shown, setShown] = useState(PAGE);
  const scroller = useRef<HTMLDivElement>(null);
  const v = useRef("");
  const atEnd = useRef(true);

  useEffect(() => {
    setData(null);
    setMissing(false);
    setOpen(null);
    setOnly(null);
    setShown(PAGE);
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

  // Each lane's colour, and which lanes the filter keeps (the chosen one and everything under it).
  const colors = useMemo(() => new Map(data?.lanes.filter((l) => l.kind !== "main").map((l, i) => [l.id, PALETTE[i % PALETTE.length]]) ?? []), [data]);
  const kept = useMemo(() => {
    if (!data || !only) return null;
    const set = new Set([only]);
    if (only === "main") return set; // the chat's own steps only
    for (const l of data.lanes) if (l.parent && set.has(l.parent)) set.add(l.id); // lanes come parent first
    return set;
  }, [data, only]);

  const all = useMemo(() => {
    if (!data) return [] as Row[];
    const rows: Row[] = [];
    for (const lane of data.lanes) {
      if (kept && !kept.has(lane.id)) continue;
      for (const e of lane.events) rows.push({ lane, e, at: e.k === "tool" ? e.start : e.at });
    }
    return rows.sort((a, b) => a.at - b.at);
  }, [data, kept]);
  const rows = all.length > shown ? all.slice(all.length - shown) : all;

  // Rails: each lane holds one from its first row to its last (to the bottom while it runs); a free
  // rail is reused by a later lane, so agents one after another share it.
  const rails = useMemo(() => {
    const first = new Map<string, number>();
    const last = new Map<string, number>();
    rows.forEach((r, i) => {
      if (!first.has(r.lane.id)) first.set(r.lane.id, i);
      last.set(r.lane.id, r.lane.running ? rows.length : i);
    });
    const col = new Map<string, number>();
    const freeAt: number[] = []; // per rail: the last row it is taken until
    const order = [...first.keys()].sort((a, b) => first.get(a)! - first.get(b)!);
    // The chat keeps the first rail when it is shown.
    const low = first.has("main") ? 1 : 0;
    for (const id of order) {
      let c = id === "main" ? 0 : freeAt.findIndex((end, i) => i >= low && end < first.get(id)!);
      if (c === -1) c = Math.max(low, freeAt.length);
      col.set(id, c);
      freeAt[c] = last.get(id)!;
    }
    return { first, last, col, count: Math.max(1, freeAt.length) };
  }, [rows]);

  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && atEnd.current) el.scrollTop = el.scrollHeight;
  }, [rows.length, data]);

  useEffect(() => {
    const key = (e: KeyboardEvent) => e.key === "Escape" && open && (e.stopPropagation(), setOpen(null));
    window.addEventListener("keydown", key, true);
    return () => window.removeEventListener("keydown", key, true);
  }, [open]);

  if (missing || !data)
    return (
      <div className="tl">
        <div className="tl-head"><b>Timeline</b></div>
        <p className="muted tl-empty">{missing ? "Nothing yet: the timeline fills in once Claude starts working in this chat." : "Loading…"}</p>
      </div>
    );

  const agents = data.lanes.filter((l) => l.kind !== "main");
  const running = agents.filter((l) => l.running).length;
  const onlyLane = only ? data.lanes.find((l) => l.id === only) : null;

  // The rails beside one row: a line for every lane alive here, a dot for the row's own.
  const railCell = (i: number, own: string | null) => (
    <span className="tl-rails" style={{ width: rails.count * RAIL }}>
      {[...rails.col].map(([id, c]) => {
        const f = rails.first.get(id)!;
        const l = rails.last.get(id)!;
        if (i < f || i > l) return null;
        const color = colors.get(id);
        const style = { left: c * RAIL + RAIL / 2 - 1, ...(color ? { "--rail": `var(--c-${color})` } : {}) } as React.CSSProperties;
        return <span key={id} className={`tl-line ${i === f ? "from" : ""} ${i === l ? "to" : ""}`} style={style} />;
      })}
      {own !== null && (
        <span className="tl-node" style={{ left: rails.col.get(own)! * RAIL + RAIL / 2 - 4, ...(colors.get(own) ? { "--rail": `var(--c-${colors.get(own)})` } : {}) } as React.CSSProperties} />
      )}
    </span>
  );

  const out: React.ReactNode[] = [];
  rows.forEach((r, i) => {
    const prev = rows[i - 1];
    if (prev && day(prev.at) !== day(r.at)) out.push(<li key={`d${i}`} className="tl-sep day">{railCell(i - 0.5, null)}<span>{day(r.at)}</span></li>);
    else if (prev && r.at - prev.at > IDLE) out.push(<li key={`g${i}`} className="tl-sep">{railCell(i - 0.5, null)}<span>{dur(r.at - prev.at)} quiet</span></li>);
    const key = `${r.lane.id}:${r.e.id}`;
    out.push(
      <Step
        key={key}
        r={r}
        now={data.now}
        rails={railCell(i, r.lane.id)}
        color={colors.get(r.lane.id)}
        isOpen={open === key}
        onOpen={() => setOpen(open === key ? null : key)}
        onLane={(id) => setOnly(id)}
        chat={chat}
        lanes={data.lanes}
      />,
    );
  });

  return (
    <div className="tl">
      <div className="tl-head">
        <b>Timeline</b>
        <span className="muted small">
          {agents.length ? `${agents.length} ${agents.length === 1 ? "agent" : "agents"}${running ? ` · ${running} running` : ""}` : "no agents yet"}
        </span>
        {agents.length > 0 && (
          <select className="tl-filter" value={only ?? ""} onChange={(e) => setOnly(e.target.value || null)} title="Show one agent (and the agents it started)">
            <option value="">Everything</option>
            <option value="main">Chat only</option>
            {agents.map((l) => <option key={l.id} value={l.id}>{l.title}{l.running ? " (running)" : ""}</option>)}
          </select>
        )}
        {onlyLane && <button className="link" onClick={() => setOnly(null)}>show everything</button>}
      </div>
      <div
        className="tl-scroll"
        ref={scroller}
        onScroll={(e) => {
          const el = e.currentTarget;
          atEnd.current = el.scrollTop + el.clientHeight >= el.scrollHeight - 40;
        }}
      >
        {all.length > rows.length && (
          <button className="tl-more link" onClick={() => { atEnd.current = false; setShown((n) => n + PAGE); }}>
            {all.length - rows.length} earlier steps · show {Math.min(PAGE, all.length - rows.length)} more
          </button>
        )}
        <ul className="tl-feed">{out}</ul>
      </div>
    </div>
  );
}

function Step({ r, now, rails, color, isOpen, onOpen, onLane, chat, lanes }: {
  r: Row; now: number; rails: React.ReactNode; color?: string; isOpen: boolean; onOpen: () => void; onLane: (id: string) => void; chat: string; lanes: TimelineLane[];
}) {
  const { lane, e } = r;
  const tag = lane.kind !== "main" && (
    <button className="tl-tag" style={{ "--rail": `var(--c-${color})` } as React.CSSProperties} title={`${lane.title} (${lane.kind}) · show only this agent`} onClick={(ev) => { ev.stopPropagation(); onLane(lane.id); }}>
      {lane.title}
    </button>
  );
  let body: React.ReactNode;
  let right: React.ReactNode = null;
  let cls = "";
  if (e.k === "tool") {
    const end = e.end ?? (lane.running ? now : undefined);
    cls = e.error ? "err" : e.end === undefined ? (lane.running ? "busy" : "lost") : "";
    const started = e.agent ? lanes.find((l) => l.id === e.agent) : undefined;
    body = (
      <>
        <span className={`tl-tool ${category(e.name)}`}>{e.name}</span>
        <span className="tl-title">{e.title}</span>
        {started && <button className="link" onClick={(ev) => { ev.stopPropagation(); onLane(started.id); }}>its steps</button>}
      </>
    );
    right = (
      <span className="tl-dur">
        {e.diff && (
          <span className="tl-diff" title={`${e.diff.added} lines added, ${e.diff.removed} removed`}>
            {e.diff.added > 0 && <span className="plus">+{e.diff.added}</span>}
            {e.diff.removed > 0 && <span className="minus">−{e.diff.removed}</span>}
          </span>
        )}
        {e.error && <span className="tl-failed">failed</span>}
        {end === undefined ? "no result" : `${dur(end - e.start)}${e.end === undefined ? " so far" : ""}`}
      </span>
    );
  } else {
    const who = e.k === "user" ? (lane.kind === "main" ? "You" : "Task") : e.k === "note" ? "Note" : "Claude";
    cls = e.k;
    body = (
      <>
        <span className={`tl-who ${e.k}`}>{who}</span>
        <span className="tl-text">{e.text}</span>
      </>
    );
  }
  return (
    <li className={`tl-step ${cls} ${isOpen ? "open" : ""}`}>
      <button className="tl-step-row" onClick={onOpen}>
        <span className="tl-time">{clock(r.at)}</span>
        {rails}
        <span className="tl-body">{tag}{body}</span>
        {right}
      </button>
      {isOpen && <Detail chat={chat} lane={lane} e={e} />}
    </li>
  );
}

function Detail({ chat, lane, e }: { chat: string; lane: TimelineLane; e: TimelineEvent }) {
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

  return (
    <div className="tl-detail">
      {failed ? <p className="muted small">Couldn't read this from the session file.</p>
        : !d ? <p className="muted small">Loading…</p>
        : d.k === "text" ? <div className="md small" dangerouslySetInnerHTML={{ __html: md(d.text) }} />
        : d.patch ? (
          <>
            <h4>{d.patch.created ? "New file" : "Diff"} <span className="tl-file">{tilde(d.patch.file)}</span></h4>
            {d.patch.hunks.length ? <PatchDiff hunks={d.patch.hunks} /> : <p className="muted small">No lines changed.</p>}
            {d.patch.cut && <p className="muted small">Cut at 5000 lines.</p>}
            <details className="tl-raw">
              <summary>Input and output</summary>
              <h4>Input</h4>
              <pre>{d.input}</pre>
              <h4>Output</h4>
              <pre>{d.output || "(no output)"}</pre>
            </details>
          </>
        )
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
  );
}
