// The work Claude started beside the chat (decision 25): a row of agent chips under the chat's
// title (what each runs now, live), the list of every agent of the chat, and one agent's view in
// the chat column (its steps, files, prompt and report). Read through the runner's
// /api/chats/:id/agents (src/agents.ts), from the session files, so it works for any server in the
// hub and in other profiles' read-only views (no Stop there).
import React, { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState } from "react";
import { useApi, type Api } from "./api";
import { Transcript } from "./chat";
import { PatchDiff } from "./changes";
import { CommitView } from "./git";
import { Output } from "./tasks";
import { md } from "./md";
import type { AgentDetail, AgentInfo, FileChange } from "../src/agents";
import type { Item, Task, TimelineDetail } from "./types";
import "./agents.css";

// ---- the chat's agents, one poller per chat however many parts show them ----

type Data = { agents: AgentInfo[]; skew: number };
type Feed = { data: Data | null; subs: Set<() => void>; v: string; timer: ReturnType<typeof setInterval> | null; busy: boolean; again: boolean };
const feeds = new Map<string, Feed>();
const POLL_MS = 2000;

function load(key: string, api: Api, chat: string) {
  const f = feeds.get(key);
  if (!f) return;
  if (f.busy) return void (f.again = true);
  f.busy = true;
  api.fetch(`/api/chats/${chat}/agents${f.v ? `?v=${encodeURIComponent(f.v)}` : ""}`)
    .then((r) => (r.ok ? r.json() : null))
    .then((j) => {
      if (!j || j.same || feeds.get(key) !== f) return;
      f.v = j.v;
      // The runner's clock may differ from this one (another server through the hub).
      f.data = { agents: j.agents ?? [], skew: Date.now() - Number(j.now ?? Date.now()) };
      for (const s of f.subs) s();
    })
    .catch(() => {})
    .finally(() => {
      f.busy = false;
      if (f.again) {
        f.again = false;
        load(key, api, chat);
      }
    });
}

// The chat's agents: asked again whenever its tasks change, and every 2 s while one runs (the step
// each one is on comes from its file, which grows without a task event).
export function useAgents(chat: string, tasks: Task[]): Data | null {
  const api = useApi();
  const key = `${api.server}\n${api.view ?? ""}\n${chat}`;
  const [, bump] = useReducer((n: number) => n + 1, 0);
  const sig = tasks.map((t) => `${t.id}:${t.status}`).join(",");
  const running = tasks.some((t) => t.status === "running" && !t.ambient);
  useEffect(() => {
    let f = feeds.get(key);
    if (!f) feeds.set(key, (f = { data: null, subs: new Set(), v: "", timer: null, busy: false, again: false }));
    f.subs.add(bump);
    return () => {
      f!.subs.delete(bump);
      if (f!.subs.size) return;
      if (f!.timer) clearInterval(f!.timer);
      feeds.delete(key);
    };
  }, [key]);
  useEffect(() => {
    const f = feeds.get(key);
    if (!f) return;
    load(key, api, chat);
    if (f.timer) clearInterval(f.timer);
    f.timer = running ? setInterval(() => load(key, api, chat), POLL_MS) : null;
  }, [key, sig, running]);
  return feeds.get(key)?.data ?? null;
}

// ---- small words ----

export function dur(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
}
// Tokens only (no cost, no share of a limit): 950, 155k, 1.2M.
export const tokens = (n: number | null) => (n === null ? "" : n < 1000 ? `${n}` : n < 1e6 ? `${Math.round(n / 1000)}k` : `${(n / 1e6).toFixed(1)}M`);
const elapsed = (a: AgentInfo, skew: number) => (a.end ?? Date.now() - skew) - a.start;

function useTick(on: boolean) {
  const [, set] = useState(0);
  useEffect(() => {
    if (!on) return;
    const t = setInterval(() => set((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [on]);
}

function Facts({ a, skew }: { a: AgentInfo; skew: number }) {
  const bits = [dur(elapsed(a, skew)), a.kind === "agent" ? `${a.tools} tool${a.tools === 1 ? "" : "s"}` : null, a.tokens !== null ? tokens(a.tokens) : null].filter(Boolean);
  return <span className="ag-facts">{bits.join(" · ")}</span>;
}

const STATUS: Record<AgentInfo["status"], string> = { running: "running", completed: "done", failed: "failed", stopped: "stopped" };

// ---- the chips under the title ----

// How many chips show: columns of at least CHIP_MIN px (room for the name beside its facts), up to
// four on a desktop (three in a column of one, so the chat keeps its room), two on a phone. The
// most recently active ones show.
const CHIP_MIN = 250;
const GAP = 6;

export function AgentChips({ chat, tasks, phone, readOnly, onOpen, onStop }: {
  chat: string; tasks: Task[]; phone: boolean; readOnly: boolean; onOpen: (id: string) => void; onStop: (task: string) => void;
}) {
  const data = useAgents(chat, tasks);
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setWidth(el.clientWidth));
    ro.observe(el);
    setWidth(el.clientWidth);
    return () => ro.disconnect();
  }, [!!data?.agents.length]);
  const agents = data?.agents ?? [];
  const running = agents.filter((a) => a.status === "running" && (a.kind === "agent" || a.background));
  useTick(running.length > 0);
  if (!agents.length) return null;
  const finished = agents.filter((a) => a.status !== "running");
  const failed = finished.filter((a) => a.status === "failed").length;
  const done = finished.length - failed;
  const cols = Math.max(1, Math.floor((width + GAP) / (CHIP_MIN + GAP)));
  const room = phone ? 2 : cols === 1 ? 3 : Math.min(4, cols * 2);
  // The most recently active ones, shown in the order they started (so chips don't swap places).
  const shown = [...running].sort((a, b) => b.active - a.active).slice(0, room).sort((a, b) => a.start - b.start);
  const more = running.length - shown.length;
  return (
    <div className="ag-row" ref={box}>
      {shown.length > 0 && (
        <div className="ag-chips" style={{ gridTemplateColumns: `repeat(${Math.min(cols, shown.length, phone ? 2 : 4)}, minmax(0, 1fr))` }}>
          {shown.map((a) => <Chip key={a.id} a={a} skew={data!.skew} readOnly={readOnly} onOpen={() => onOpen(a.id)} onStop={onStop} />)}
        </div>
      )}
      {(more > 0 || finished.length > 0) && (
        <div className="ag-sum">
          {more > 0 && <button className="ag-pill" onClick={() => onOpen("")} title="Every agent of this chat">+{more} more</button>}
          {finished.length > 0 && (
            <button className="ag-pill done" onClick={() => onOpen("")} title={`${done} finished${failed ? `, ${failed} failed` : ""}: every agent of this chat`}>
              {done > 0 && <span className="ok">✓ {done}</span>}
              {failed > 0 && <span className="bad">✕ {failed}</span>}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

// A job's progress (its `##progress` line): a bar and "done/total label".
function Bar({ p, status }: { p: NonNullable<AgentInfo["progress"]>; status: AgentInfo["status"] }) {
  const pct = Math.round((p.done / p.total) * 100);
  return (
    <span className={`ag-bar ${status}`} role="progressbar" aria-valuemin={0} aria-valuemax={p.total} aria-valuenow={p.done} title={`${p.done} of ${p.total}`}>
      <span className="ag-bar-fill" style={{ width: `${pct}%` }} />
      <span className="ag-bar-text">{p.done}/{p.total}{p.label ? ` ${p.label}` : ""} · {pct}%</span>
    </span>
  );
}

function Chip({ a, skew, readOnly, onOpen, onStop }: { a: AgentInfo; skew: number; readOnly: boolean; onOpen: () => void; onStop: (task: string) => void }) {
  return (
    <div className={`ag-chip ${a.status}`} role="button" tabIndex={0} onClick={onOpen} onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && (e.preventDefault(), onOpen())} title={a.name}>
      <span className="ag-l1">
        {a.status === "running" ? <span className="ag-spin" aria-label="running" /> : <span className={`ag-mark ${a.status}`}>{a.status === "failed" ? "✕" : "✓"}</span>}
        <b className="ag-name">{a.name}</b>
        <Facts a={a} skew={skew} />
      </span>
      {a.progress && <Bar p={a.progress} status={a.status} />}
      <span className="ag-l2">{a.step ?? (a.kind === "agent" ? "starting…" : "")}{a.children > 0 ? ` · +${a.children}` : ""}</span>
      {!readOnly && a.task && a.status === "running" && (
        <button className="ag-stop" onClick={(e) => { e.stopPropagation(); onStop(a.task!); }} title="Stop this agent">Stop</button>
      )}
    </div>
  );
}

// ---- the agent view and the list, in the chat column ----

// Which agent view shows, and the phone's back: opening one pushes a history entry (the system's
// back swipe closes it, as the phone's chat screens do); app.tsx asks onPop first.
export function useAgentView(chatKey: string, phone: boolean) {
  const [at, setAt] = useState<{ chat: string; id: string } | null>(null);
  const ref = useRef({ at, chatKey, phone });
  ref.current = { at, chatKey, phone };
  const shown = at && at.chat === chatKey ? at.id : null;
  return useMemo(() => ({
    // id "" = the list of every agent of the chat
    shown,
    open: (id: string) => {
      const { chatKey, phone } = ref.current;
      if (phone) history.pushState({ ...history.state, agent: id, agentDepth: (Number(history.state?.agentDepth) || 0) + 1 }, "");
      setAt({ chat: chatKey, id });
    },
    // Back to the chat: past every agent view pushed since it (the list, then an agent).
    close: () => {
      const depth = Number(history.state?.agentDepth) || 0;
      if (ref.current.phone && depth > 0) return history.go(-depth);
      setAt(null);
    },
    // A back (or forward) step while an agent view is open: true when it was only that view's.
    onPop: (e: PopStateEvent): boolean => {
      const { at, chatKey } = ref.current;
      if (!at || at.chat !== chatKey) return false;
      const id = e.state?.agent;
      if (typeof id === "string") {
        setAt({ chat: chatKey, id });
        return true;
      }
      setAt(null);
      return e.state?.screen === "chat";
    },
  }), [shown]);
}

type Tab = "steps" | "files" | "prompt" | "report";

export function AgentScreen({ chat, id, tasks, readOnly, onBack, onOpen, onStop, onTimeline }: {
  chat: string; id: string; tasks: Task[]; readOnly: boolean; onBack: () => void; onOpen: (id: string) => void; onStop: (task: string) => void; onTimeline: () => void;
}) {
  const data = useAgents(chat, tasks);
  return (
    <div className="ag-screen">
      <div className="ag-top">
        <button className="ag-back" onClick={onBack}>
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M12.5 4L6.5 10l6 6" /></svg>
          Back to the chat
        </button>
        {id === "" && <button className="link" onClick={onTimeline} title="Every agent's steps on one time axis (Alt+T)">Timeline</button>}
      </div>
      {id === "" ? <AgentList data={data} readOnly={readOnly} onOpen={onOpen} onStop={onStop} />
        : <AgentView key={id} chat={chat} info={data?.agents.find((a) => a.id === id) ?? null} id={id} skew={data?.skew ?? 0} tasks={tasks} readOnly={readOnly} onStop={onStop} />}
    </div>
  );
}

function AgentList({ data, readOnly, onOpen, onStop }: { data: Data | null; readOnly: boolean; onOpen: (id: string) => void; onStop: (task: string) => void }) {
  const agents = data?.agents ?? [];
  useTick(agents.some((a) => a.status === "running"));
  if (!data) return <p className="muted small ag-pad">Loading…</p>;
  if (!agents.length) return <p className="muted small ag-pad">No agents in this chat.</p>;
  const running = agents.filter((a) => a.status === "running").length;
  return (
    <div className="ag-scroll">
      <h2 className="ag-title">Agents <span className="muted small">{running ? `${running} running · ` : ""}{agents.length} in this chat</span></h2>
      <div className="ag-list">
        {agents.map((a) => <Chip key={a.id} a={a} skew={data.skew} readOnly={readOnly} onOpen={() => onOpen(a.id)} onStop={onStop} />)}
      </div>
    </div>
  );
}

function AgentView({ chat, id, info, skew, tasks, readOnly, onStop }: {
  chat: string; id: string; info: AgentInfo | null; skew: number; tasks: Task[]; readOnly: boolean; onStop: (task: string) => void;
}) {
  const api = useApi();
  const [d, setD] = useState<AgentDetail | { error: string } | null>(null);
  const running = info?.status === "running";
  const shell = info?.kind === "shell";
  const [tab, setTab] = useState<Tab>("steps");
  useTick(running);
  // Its facts come with the list; the rest (prompt, report, files, branch) again every 5 s while it runs.
  useEffect(() => {
    let dead = false;
    const get = () => api.fetch(`/api/chats/${chat}/agents/${encodeURIComponent(id)}`).then((r) => r.json()).then((j) => !dead && setD(j)).catch(() => !dead && setD({ error: "can't read it" }));
    get();
    const t = running ? setInterval(get, 5000) : undefined;
    return () => { dead = true; clearInterval(t); };
  }, [api, chat, id, running, info?.end]);
  const a = info ?? (d && !("error" in d) ? d : null);
  if (!a) return <p className="muted small ag-pad">{d && "error" in d ? `Not found (${d.error}).` : "Loading…"}</p>;
  const detail = d && !("error" in d) ? d : null;
  const task = a.task ? tasks.find((t) => t.id === a.task) : undefined;
  const tabs: [Tab, string][] = shell
    ? [["steps", "Output"], ["prompt", "Command"]]
    : [["steps", "Steps"], ["files", detail?.files.length ? `Files ${detail.files.length}` : "Files"], ["prompt", "Prompt"], ["report", "Report"]];
  const facts: [string, React.ReactNode][] = [
    [running ? "Running for" : "Took", dur(elapsed(a, skew))],
    ...(shell ? [] : [["Tools", String(a.tools)] as [string, string]]),
    ...(a.tokens !== null ? [["Tokens", tokens(a.tokens)] as [string, string]] : []),
    ...(a.model || a.effort ? [["Model", [a.model, a.effort ? `${a.effort} effort` : !shell ? "usual effort" : null].filter(Boolean).join(" · ")] as [string, string]] : []),
    ["Kind", shell ? "background shell" : `${a.type}${a.background ? " · background" : ""}`],
    ...(a.worktree ? [["Worktree", <>{a.worktree.branch}{detail?.git ? ` · ${detail.git.commits.length} commit${detail.git.commits.length === 1 ? "" : "s"}` : ""}</>] as [string, React.ReactNode]] : []),
  ];
  return (
    <div className="ag-scroll">
      <div className="ag-head">
        <h2 className="ag-title ag-name-line">
          {running ? <span className="ag-spin" aria-label="running" /> : <span className={`ag-mark ${a.status}`}>{a.status === "failed" ? "✕" : "✓"}</span>}
          <span className="ag-title-text">{a.name}</span>
        </h2>
        <div className="ag-state">
          <span className={`chip ${a.status === "failed" ? "red" : a.status === "running" ? "amber" : a.status === "completed" ? "green" : ""}`}>{STATUS[a.status]}</span>
          {!readOnly && running && a.task && <button className="task-stop" onClick={() => onStop(a.task!)}>Stop</button>}
        </div>
        <dl className="ag-dl">{facts.map(([k, v]) => <React.Fragment key={k}><dt>{k}</dt><dd>{v}</dd></React.Fragment>)}</dl>
        {a.step && running && <p className="ag-now"><span className="muted">Now</span> <code>{a.step}</code></p>}
      </div>
      <div className="ag-tabs" role="tablist">
        {tabs.map(([t, label]) => <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? "on" : ""} onClick={() => setTab(t)}>{label}</button>)}
      </div>
      <div className="ag-body">
        {tab === "steps" && (shell ? (task ? <Output chat={chat} task={task} /> : <p className="muted small">Its output shows while the runner knows the command (it ran {a.status === "running" ? "" : "and ended "}before the runner's last start).</p>)
          : a.lane ? <Steps chat={chat} lane={a.lane} live={running} /> : <p className="muted small">Its steps show once its file is written.</p>)}
        {tab === "files" && <Files chat={chat} a={a} detail={detail} />}
        {tab === "prompt" && (detail ? detail.prompt ? <pre className="ag-prompt">{detail.prompt}</pre> : <p className="muted small">The prompt isn't in the session files.</p> : <p className="muted small">Loading…</p>)}
        {tab === "report" && (running ? <p className="muted small">It reports when it finishes.</p>
          : detail ? detail.report ? <div className="md ag-report" dangerouslySetInnerHTML={{ __html: md(detail.report) }} /> : <p className="muted small">No report in the session files.</p>
          : <p className="muted small">Loading…</p>)}
      </div>
    </div>
  );
}

// Its transcript from its own file, in the chat's compact form; only what is new comes again.
function Steps({ chat, lane, live }: { chat: string; lane: string; live: boolean }) {
  const api = useApi();
  const [items, setItems] = useState<Item[] | null>(null);
  const have = useRef<{ v: string; items: Item[] }>({ v: "", items: [] });
  useEffect(() => {
    let dead = false;
    have.current = { v: "", items: [] };
    const get = () => {
      const cur = have.current.items;
      // Items up to the first tool still without its result are whole: only the rest comes again.
      const open = cur.findIndex((it) => it.k === "tool" && !it.result);
      const from = open < 0 ? cur.length : open;
      api.fetch(`/api/chats/${chat}/agents/${encodeURIComponent(lane)}/steps?from=${from}${have.current.v ? `&v=${have.current.v}` : ""}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((j) => {
          if (dead || !j || j.same) return;
          const next = [...have.current.items.slice(0, j.from), ...j.items];
          have.current = { v: j.v, items: next };
          setItems(next);
        })
        .catch(() => {});
    };
    get();
    const t = live ? setInterval(get, 2000) : undefined;
    return () => { dead = true; clearInterval(t); };
  }, [api, chat, lane, live]);
  if (!items) return <p className="muted small">Loading…</p>;
  if (!items.length) return <p className="muted small">No steps yet.</p>;
  return <div className="ag-steps"><Transcript items={items} live={live} /></div>;
}

// Its edits by file (each opening its diffs), and for an agent in a worktree its branch: the
// commits, and the whole change since the branch was made.
function Files({ chat, a, detail }: { chat: string; a: AgentInfo; detail: AgentDetail | null }) {
  const [rev, setRev] = useState<string | null>(null);
  if (!detail) return <p className="muted small">Loading…</p>;
  const git = detail.git;
  const base = `/api/chats/${chat}/agents/${encodeURIComponent(a.id)}/git?`;
  return (
    <div className="ag-files">
      {detail.files.length ? (
        <ul className="ag-file-list">{detail.files.map((f) => <FileRow key={f.path} chat={chat} lane={a.lane!} f={f} />)}</ul>
      ) : <p className="muted small">No Edit or Write calls{a.status === "running" ? " yet" : ""}.</p>}
      {a.worktree && (
        <section className="ag-git">
          <h3>Branch <code>{a.worktree.branch}</code></h3>
          {!git ? <p className="muted small">{detail.error ? `Can't read it: ${detail.error}` : "The branch is gone."}</p> : <>
            {!git.worktree && <p className="muted small">Its worktree folder is gone; the branch is read from the repo.</p>}
            <ul className="ag-commits">
              <li>
                <button className={`ag-commit ${rev === "BRANCHED" ? "on" : ""}`} onClick={() => setRev(rev === "BRANCHED" ? null : "BRANCHED")} disabled={!git.base}>
                  <b>All of it since it branched</b>
                  <span className="muted small">{git.base ? `from ${git.base.slice(0, 8)}${git.worktree ? ", uncommitted work too" : ""}` : "where it branched isn't known"}</span>
                </button>
              </li>
              {git.commits.map((c) => (
                <li key={c.hash}>
                  <button className={`ag-commit ${rev === c.hash ? "on" : ""}`} onClick={() => setRev(rev === c.hash ? null : c.hash)}>
                    <code className="h">{c.hash.slice(0, 8)}</code>
                    <span className="ag-subj">{c.subject}</span>
                    <span className="muted small">{c.when}</span>
                  </button>
                </li>
              ))}
              {!git.commits.length && <li className="muted small">No commits on the branch yet.</li>}
            </ul>
            {rev && <div className="ag-commit-view"><CommitView base={base} rev={rev} close={() => setRev(null)} /></div>}
          </>}
        </section>
      )}
    </div>
  );
}

function FileRow({ chat, lane, f }: { chat: string; lane: string; f: FileChange }) {
  const [open, setOpen] = useState(false);
  return (
    <li className={`change ${open ? "open" : ""}`}>
      <button className="change-row" onClick={() => setOpen(!open)}>
        <span className={`chev ${open ? "open" : ""}`}>›</span>
        <span className="change-path" title={f.path}>{f.rel}</span>
        {f.edits.length > 1 && <span className="muted small">{f.edits.length} edits</span>}
        <span className="counts"><span className="plus">+{f.added}</span> <span className="minus">−{f.removed}</span></span>
      </button>
      {open && f.edits.map((e) => <EditDiff key={e.event} chat={chat} lane={lane} event={e.event} name={e.name} />)}
    </li>
  );
}

// One edit's patch, as the timeline opens it (an agent's file has no saved patch: it is made from
// the call, numbered within the replaced text).
function EditDiff({ chat, lane, event, name }: { chat: string; lane: string; event: string; name: string }) {
  const api = useApi();
  const [d, setD] = useState<TimelineDetail | null | false>(null);
  useEffect(() => {
    let dead = false;
    api.fetch(`/api/chats/${chat}/timeline/${lane}/${encodeURIComponent(event)}`).then((r) => (r.ok ? r.json() : Promise.reject())).then((j) => !dead && setD(j), () => !dead && setD(false));
    return () => { dead = true; };
  }, [api, chat, lane, event]);
  if (d === null) return <p className="muted small">Loading…</p>;
  if (d === false || d.k !== "tool" || !d.patch) return <p className="muted small">No diff for this {name}.</p>;
  return (
    <div className="ag-diff">
      <span className="muted small">{name === "Write" ? "Written whole" : name}</span>
      <PatchDiff hunks={d.patch.hunks} />
    </div>
  );
}

// ---- the rail's third state ----

// Claude's turn ended but work it started still runs: a dashed amber chalk ring round the chat's
// circle, with how many (not the working dots).
export function AgentsRing({ n }: { n: number }) {
  return (
    <>
      <svg className="ag-ring" viewBox="0 0 48 48" aria-hidden="true"><circle cx="24" cy="24" r="21" /></svg>
      <span className="state-badge agents" title={`${n} agent${n === 1 ? "" : "s"} still working`}>{n || ""}</span>
    </>
  );
}
