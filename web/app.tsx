import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Messages, Composer } from "./chat";
import { PictureView } from "./picture";
import { AskPanel, PermitPanel } from "./panels";
import { TasksPanel } from "./tasks";
import { ChangesPanel, clip, tilde } from "./changes";
import { Meters, Pickers } from "./usage";
import { ColorPicker, chatColorVar } from "./colors";
import type { Ask, ChangeMeta, ChatEvent, ChatRow, Context, Item, Meta, Permit, Picture, Scene, Settings, Task } from "./types";
import "./style.css";

const empty: Scene = { title: "", nodes: [], edges: [], groups: [], cards: [] };

type Live = {
  id: string;
  loaded: boolean;
  items: Item[];
  partial: string;
  running: boolean;
  since: number;
  note?: string;
  error?: string;
  scene: Scene;
  changed: string[];
  seq: number;
  pictures: Picture[];
  asks: Ask[];
  permits: Permit[];
  lastTurn?: { drew: boolean; stopped?: boolean };
  tasks: Task[];
  subs: Record<string, Item[]>;
  context: Context | null;
  settings: Settings;
  model: string;
  changes: ChangeMeta[];
};

const fresh = (id: string): Live => ({
  id, loaded: false, items: [], partial: "", running: false, since: Date.now(),
  scene: empty, changed: [], seq: 0, pictures: [], asks: [], permits: [],
  tasks: [], subs: {}, context: null, settings: { model: null, effort: null, folder: null, color: null }, model: "", changes: [],
});

const idFromPath = () => location.pathname.match(/^\/c\/([0-9a-f-]{36})/)?.[1] ?? null;

const store = {
  get: (k: string) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v); } catch {} },
};

// A WebSocket that comes back by itself and resends what was queued.
function useSocket(onMsg: (m: any) => void, onOpen: () => void) {
  const ws = useRef<WebSocket | null>(null);
  const queue = useRef<string[]>([]);
  const [up, setUp] = useState(false);
  const handlers = useRef({ onMsg, onOpen });
  handlers.current = { onMsg, onOpen };
  useEffect(() => {
    let dead = false;
    let retry = 300;
    const connect = () => {
      const s = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`);
      ws.current = s;
      s.onopen = () => {
        retry = 300;
        setUp(true);
        handlers.current.onOpen();
        for (const q of queue.current.splice(0)) s.send(q);
      };
      s.onmessage = (e) => handlers.current.onMsg(JSON.parse(e.data));
      s.onclose = () => {
        setUp(false);
        if (!dead) setTimeout(connect, (retry = Math.min(retry * 2, 5000)));
      };
    };
    connect();
    return () => { dead = true; ws.current?.close(); };
  }, []);
  const send = useCallback((m: unknown) => {
    const s = JSON.stringify(m);
    if (ws.current?.readyState === WebSocket.OPEN) ws.current.send(s);
    else queue.current.push(s);
  }, []);
  return { send, up };
}

function ago(t: number) {
  const s = (Date.now() - t) / 1000;
  if (s < 60) return "now";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  if (s < 86400 * 7) return `${Math.floor(s / 86400)}d`;
  return new Date(t).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function apply(l: Live, ev: ChatEvent): Live {
  switch (ev.t) {
    case "user": return { ...l, items: [...l.items, ev.item], error: undefined, lastTurn: undefined };
    case "delta": return { ...l, partial: l.partial + ev.text };
    case "items": return { ...l, partial: "", items: [...l.items, ...ev.items] };
    case "tool_result":
      return { ...l, items: l.items.map((it) => (it.k === "tool" && it.id === ev.id ? { ...it, result: { error: ev.error, text: ev.text } } : it)) };
    case "scene": {
      const pics = l.pictures.filter((p) => p.turn !== ev.turn);
      return { ...l, scene: ev.scene, changed: ev.changed, seq: l.seq + 1, pictures: [...pics, { turn: ev.turn, scene: ev.scene, changed: ev.changed, at: Date.now() }] };
    }
    case "ask": return { ...l, asks: [...l.asks.filter((a) => a.id !== ev.ask.id), ev.ask] };
    case "ask_done": return { ...l, asks: l.asks.filter((a) => a.id !== ev.id) };
    case "permit": return { ...l, permits: [...l.permits.filter((p) => p.id !== ev.permit.id), ev.permit] };
    case "permit_done": return { ...l, permits: l.permits.filter((p) => p.id !== ev.id) };
    case "status": return { ...l, running: ev.running, note: ev.note, since: ev.running && !l.running ? Date.now() : l.since, partial: ev.running ? l.partial : "" };
    case "turn_end": return { ...l, lastTurn: { drew: ev.drew, stopped: ev.stopped }, error: ev.error };
    case "error": return { ...l, error: ev.text };
    case "tasks": return { ...l, tasks: ev.tasks };
    case "sub": return { ...l, subs: { ...l.subs, [ev.parent]: [...(l.subs[ev.parent] ?? []), ...ev.items].slice(-300) } };
    case "sub_result":
      return { ...l, subs: { ...l.subs, [ev.parent]: (l.subs[ev.parent] ?? []).map((it) => (it.k === "tool" && it.id === ev.id ? { ...it, result: { error: ev.error, text: ev.text } } : it)) } };
    case "context": return { ...l, context: ev.context };
    case "settings": return { ...l, settings: ev.settings, model: ev.model || l.model };
    case "changes": return { ...l, changes: [...l.changes, ...ev.changes] };
  }
  return l;
}

// Below this width the chat list slides over the chat instead of taking a column.
function useNarrow() {
  const q = "(max-width: 1099px)";
  const [narrow, setNarrow] = useState(() => matchMedia(q).matches);
  useEffect(() => {
    const m = matchMedia(q);
    const on = () => setNarrow(m.matches);
    m.addEventListener("change", on);
    return () => m.removeEventListener("change", on);
  }, []);
  return narrow;
}

function App() {
  const [chats, setChats] = useState<ChatRow[]>([]);
  const [all, setAll] = useState(store.get("all") === "1");
  const [archivedView, setArchivedView] = useState(false);
  // Every chat seen in any list, so the open one keeps its title while the other list shows.
  const known = useRef(new Map<string, ChatRow>());
  const [search, setSearch] = useState("");
  const [sidebar, setSidebarState] = useState(store.get("sidebar") !== "0");
  const narrow = useNarrow();
  const [drawer, setDrawer] = useState(false);
  const toggleSidebar = useCallback(() => {
    if (narrow) return setDrawer((d) => !d);
    setSidebarState((s) => { store.set("sidebar", s ? "0" : "1"); return !s; });
  }, [narrow]);
  const showSidebar = narrow ? drawer : sidebar;
  const [current, setCurrent] = useState<string>(() => idFromPath() ?? crypto.randomUUID());
  const [live, setLive] = useState<Live>(() => fresh(current));
  const [viewing, setViewing] = useState<string | null>(null);
  const [split, setSplit] = useState(() => Number(store.get("split")) || 0.42);
  // Folding the picture pane gives the chat the full width; foldedAt marks which pictures are unseen.
  const [foldedAt, setFoldedAt] = useState(() => (store.get("folded") === "1" ? Date.now() : 0));
  const toggleFold = useCallback(() => setFoldedAt((f) => { store.set("folded", f ? "0" : "1"); return f ? 0 : Date.now(); }), []);
  const [editing, setEditing] = useState(false);
  const [meta, setMeta] = useState<Meta>({ models: [], commands: [], limits: [], account: "", defaultModel: "", at: 0 });
  const currentRef = useRef(current);
  currentRef.current = current;

  const loadChats = useCallback(async () => {
    const r = await fetch(`/api/chats?all=${all ? 1 : 0}&archived=${archivedView ? 1 : 0}`);
    if (!r.ok) return;
    const rows: ChatRow[] = await r.json();
    for (const c of rows) known.current.set(c.id, c);
    setChats(rows);
  }, [all, archivedView]);
  useEffect(() => { loadChats(); store.set("all", all ? "1" : "0"); }, [loadChats]);

  const { send, up } = useSocket(
    (m) => {
      if (m.t === "chats_changed") return void loadChats();
      if (m.t === "meta") return void setMeta(m.meta);
      if (m.chat !== currentRef.current) return;
      if (m.t === "history") {
        const last = m.pictures.at(-1);
        setLive((l) => ({
          ...l, loaded: true, items: m.items, partial: m.partial, running: m.running, since: m.since || Date.now(), scene: m.scene,
          changed: last?.changed ?? [], seq: l.seq + 1, pictures: m.pictures, asks: m.asks, permits: m.permits,
          tasks: m.tasks ?? [], subs: m.subs ?? {}, context: m.context, settings: m.settings, model: m.model, changes: m.changes ?? [],
        }));
      } else if (m.t === "event") setLive((l) => apply(l, m.ev));
    },
    () => send({ t: "open", chat: currentRef.current }),
  );

  const open = useCallback((id: string, push = true) => {
    setCurrent(id);
    setLive(fresh(id));
    setViewing(null);
    setEditing(false);
    setDrawer(false);
    if (push) history.pushState(null, "", `/c/${id}`);
    send({ t: "open", chat: id });
  }, [send]);

  const newChat = useCallback(() => open(crypto.randomUUID()), [open]);

  useEffect(() => {
    const pop = () => open(idFromPath() ?? crypto.randomUUID(), false);
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, [open]);

  const row = chats.find((c) => c.id === current) ?? known.current.get(current);
  const title = row?.title ?? (live.items.length ? "New chat" : "New chat");
  useEffect(() => { document.title = `${title} · Claudia`; }, [title]);

  const onSend = (text: string, images: { media_type: string; data: string }[]) => {
    setViewing(null);
    // A typed message while Claude is asking answers the question.
    if (live.asks.length && text && !images.length) {
      const ask = live.asks[0];
      send({ t: "answer", chat: current, id: ask.id, answers: Object.fromEntries(ask.questions.map((q) => [q.question, text])) });
      return;
    }
    if (!idFromPath()) history.replaceState(null, "", `/c/${current}`);
    send({ t: "send", chat: current, text, images: images.map(({ media_type, data }) => ({ media_type, data })) });
  };
  const onStop = () => send({ t: "stop", chat: current });

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape" && live.running) { e.preventDefault(); onStop(); }
      const typing = (e.target as HTMLElement).closest?.("input, textarea, [contenteditable]");
      if (!typing && !e.altKey && !e.ctrlKey && !e.metaKey && (e.key === "ArrowLeft" || e.key === "ArrowRight")) {
        e.preventDefault();
        showPicture(picIndex + (e.key === "ArrowLeft" ? -1 : 1));
      }
      if (e.altKey && e.key.toLowerCase() === "n") { e.preventDefault(); newChat(); }
      if (e.altKey && e.key.toLowerCase() === "p") { e.preventDefault(); toggleFold(); }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "b") {
        e.preventDefault();
        toggleSidebar();
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  });

  const rename = async (t: string) => {
    setEditing(false);
    if (!t.trim() || t === title) return;
    await fetch(`/api/chats/${current}`, { method: "PATCH", body: JSON.stringify({ title: t.trim() }) });
  };
  // Archive hides the chat and moves on to the next one; in the archived view it brings it back.
  const setArchived = async (archived: boolean) => {
    await fetch(`/api/chats/${current}`, { method: "PATCH", body: JSON.stringify({ archived }) });
    if (!archived) return;
    const next = chats.find((c) => c.id !== current);
    if (next) open(next.id);
    else newChat();
  };

  // Which picture to show: live, or the one that was current at an older message.
  const pictureTurns = useMemo(() => new Set(live.pictures.map((p) => p.turn)), [live.pictures]);
  const shown = useMemo(() => {
    if (!viewing) return { scene: live.scene, changed: live.changed, seq: live.seq };
    const users = live.items.filter((i) => i.k === "user").map((i) => (i as any).uuid as string);
    const upTo = users.indexOf(viewing);
    const pos = new Map(users.map((u, i) => [u, i]));
    const pic = live.pictures.filter((p) => (pos.get(p.turn) ?? Infinity) <= upTo).at(-1);
    return pic ? { scene: pic.scene, changed: pic.changed, seq: live.seq + 1000 + upTo } : { scene: empty, changed: [], seq: 0 };
  }, [viewing, live.scene, live.changed, live.seq, live.pictures, live.items]);

  // Changes of the turn being looked at: an earlier turn when stepping back, else the latest turn with changes.
  const changeTurns = useMemo(() => {
    const m = new Map<string, number>();
    for (const c of live.changes) m.set(c.turn, (m.get(c.turn) ?? 0) + 1);
    return m;
  }, [live.changes]);
  const changesTurn = viewing ?? live.changes.at(-1)?.turn ?? null;
  const shownChanges = changesTurn ? live.changes.filter((c) => c.turn === changesTurn) : [];
  const lastUser = [...live.items].reverse().find((i) => i.k === "user") as Extract<Item, { k: "user" }> | undefined;
  const changesLabel = viewing ? "Earlier turn" : changesTurn === lastUser?.uuid ? "This turn" : "Last turn with changes";

  // Step through the pictures Claude made in this chat; the newest one is "live".
  const picIndex = viewing ? live.pictures.findIndex((p) => p.turn === viewing) : live.pictures.length - 1;
  const showPicture = (i: number) => {
    if (i < 0 || i >= live.pictures.length) return;
    setViewing(i === live.pictures.length - 1 ? null : live.pictures[i].turn);
  };

  const dragSplit = (e: React.PointerEvent) => {
    const main = (e.currentTarget.parentElement as HTMLElement).getBoundingClientRect();
    const move = (m: PointerEvent) => setSplit(Math.min(0.7, Math.max(0.25, (m.clientX - main.left) / main.width)));
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      setSplit((s) => { store.set("split", String(s)); return s; });
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };

  // A question or permission request waits in the pane, so it opens the pane while it's pending.
  // The chat's colour themes the whole window (style.css, ":root[data-chat]").
  useEffect(() => {
    const root = document.documentElement;
    if (live.settings.color) {
      root.dataset.chat = live.settings.color;
      root.style.setProperty("--hue", `var(--c-${live.settings.color})`);
    } else {
      delete root.dataset.chat;
      root.style.removeProperty("--hue");
    }
  }, [live.settings.color]);

  const folded = foldedAt > 0 && !live.asks.length && !live.permits.length;
  const unseen = folded && (live.pictures.at(-1)?.at ?? 0) > foldedAt;

  const q = search.trim().toLowerCase();
  const list = q ? chats.filter((c) => c.title.toLowerCase().includes(q)) : chats;

  return (
    <div className={`app ${showSidebar ? "" : "no-sidebar"} ${narrow ? "narrow" : ""}`}>
      {narrow && drawer && <div className="backdrop" onClick={() => setDrawer(false)} />}
      <button className="icon menu-toggle" onClick={toggleSidebar} title={showSidebar ? "Hide chats (Ctrl+B)" : "Show chats (Ctrl+B)"} aria-expanded={showSidebar}>☰</button>
      <div className="sidebar-slot">
        {!showSidebar && (
          <nav className="rail" aria-label="Chats">
            <button className="rail-dot add" onClick={newChat} title="New chat (Alt+N)">+</button>
            {list.map((c) => (
              <button
                key={c.id}
                className={`rail-dot ${c.id === current ? "on" : ""} ${c.running ? "running" : ""}`}
                style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}
                onClick={() => open(c.id)}
                title={c.title}
                aria-label={c.title}
              >
                {(c.title.trim()[0] ?? "?").toUpperCase()}
              </button>
            ))}
          </nav>
        )}
        <aside className="sidebar" aria-hidden={!showSidebar} inert={!showSidebar}>
          <div className="brand"><span className="logo" />Claudia</div>
          <button className="new" onClick={newChat} title="New chat (Alt+N)">New chat</button>
          <input className="search" placeholder="Search chats" value={search} onChange={(e) => setSearch(e.target.value)} />
          <nav>
            {!row && live.items.length > 0 && <div className="chat-row active"><span className="t">New chat</span></div>}
            {list.map((c) => (
              <button
                key={c.id}
                className={`chat-row ${c.id === current ? "active" : ""} ${c.color ? "colored" : ""}`}
                style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}
                onClick={() => open(c.id)}
              >
                {c.running && <span className="run-dot" title="Working" />}
                <span className="t">{c.title}</span>
                <span className="when">{ago(c.at)}</span>
              </button>
            ))}
            {!list.length && <p className="muted small">{q ? "No match." : archivedView ? "Nothing archived." : "No chats yet."}</p>}
          </nav>
          <div className="side-foot">
            <button className={`archived-toggle ${archivedView ? "on" : ""}`} onClick={() => setArchivedView((v) => !v)}>
              <ArchiveIcon /> {archivedView ? "Back to chats" : "Archived"}
            </button>
            <label className="toggle">
              <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
              Show all ~/Work sessions
            </label>
          </div>
        </aside>
      </div>
      <main className={`split ${folded ? "folded" : ""}`} style={{ gridTemplateColumns: folded ? "minmax(300px, 1fr) 6px 38px" : `minmax(300px, ${Math.round(split * 1000)}fr) 6px minmax(280px, ${Math.round((1 - split) * 1000)}fr)` }}>
        <section className="left">
          <header className={`chat-head ${live.settings.color ? "colored" : ""}`}>
            <ColorPicker color={live.settings.color} onPick={(color) => send({ t: "settings", chat: current, color })} />
            {editing ? (
              <input
                className="title-edit"
                autoFocus
                defaultValue={title}
                onBlur={(e) => rename(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") rename(e.currentTarget.value); if (e.key === "Escape") setEditing(false); }}
              />
            ) : (
              <h1 onDoubleClick={() => row && setEditing(true)} title={row ? "Double-click to rename" : ""}>{title}</h1>
            )}
            {live.settings.folder && <span className="folder-chip" title={`Working folder: ${live.settings.folder}`}>{clip(tilde(live.settings.folder), 28)}</span>}
            {row && (
              <div className="head-actions">
                <button className="icon" onClick={() => setEditing(true)} title="Rename">✎</button>
                {row.archived ? (
                  <button className="icon" onClick={() => setArchived(false)} title="Unarchive"><ArchiveIcon out /></button>
                ) : (
                  <button className="icon" onClick={() => setArchived(true)} title="Archive"><ArchiveIcon /></button>
                )}
              </div>
            )}
            {!up && <span className="offline">reconnecting…</span>}
          </header>
          <Messages
            items={live.items}
            partial={live.partial}
            running={live.running}
            runningSince={live.since}
            note={live.asks.length ? "Waiting for your answer" : live.permits.length ? "Waiting for your OK" : live.note}
            error={live.error}
            stopped={!!live.lastTurn?.stopped}
            pictureTurns={pictureTurns}
            changeTurns={changeTurns}
            viewing={viewing}
            onView={setViewing}
          />
          <Composer
            running={live.running}
            asking={live.asks.length > 0}
            onSend={onSend}
            onStop={onStop}
            focusKey={current}
            commands={meta.commands}
            tools={<Pickers meta={meta} settings={live.settings} model={live.model} onSettings={(next) => send({ t: "settings", chat: current, ...next })} />}
          />
          <Meters meta={meta} context={live.context} />
        </section>
        <div className="divider" onPointerDown={folded ? undefined : dragSplit} />
        {folded ? (
          <section className="right fold-strip">
            <button className={`unfold ${unseen ? "unseen" : ""}`} onClick={toggleFold} title={unseen ? "New picture · show (Alt+P)" : "Show picture (Alt+P)"}>
              <span className="chev">‹</span>
              <span className="fold-label">Picture</span>
              {unseen && <span className="fold-dot" />}
            </button>
          </section>
        ) : (
        <section className="right">
          <div className="right-scroll">
          {(live.asks.length > 0 || live.permits.length > 0 || live.tasks.length > 0 || shownChanges.length > 0) && (
            <div className="interface">
              {live.tasks.length > 0 && <TasksPanel chat={current} tasks={live.tasks} subs={live.subs} onStop={(task) => send({ t: "stop_task", chat: current, task })} />}
              {shownChanges.length > 0 && <ChangesPanel chat={current} changes={shownChanges} folder={live.settings.folder} label={changesLabel} />}
              {live.permits.map((p) => <PermitPanel key={p.id} permit={p} onPermit={(allow) => send({ t: "permit", chat: current, id: p.id, allow })} />)}
              {live.asks.map((a) => <AskPanel key={a.id} ask={a} onAnswer={(answers) => send({ t: "answer", chat: current, id: a.id, answers })} />)}
            </div>
          )}
          <div className={`stage ${viewing ? "past" : ""}`}>
            <PictureView scene={shown.scene} changed={shown.changed} seq={shown.seq} />
          </div>
          </div>
          <footer className="timeline">
            {live.pictures.length > 1 && (
              <div className="pager">
                <button className="chev-btn" disabled={picIndex <= 0} onClick={() => showPicture(picIndex - 1)} title="Previous picture (←)">‹</button>
                <span className="muted small">{picIndex + 1} / {live.pictures.length}</span>
                <button className="chev-btn" disabled={picIndex >= live.pictures.length - 1} onClick={() => showPicture(picIndex + 1)} title="Next picture (→)">›</button>
              </div>
            )}
            {live.pictures.length > 0 && (
              <div className="dots">
                {live.pictures.map((p, i) => {
                  const on = viewing ? p.turn === viewing : i === live.pictures.length - 1;
                  return <button key={p.turn} className={`tdot ${on ? "on" : ""}`} title={`Picture ${i + 1}`} onClick={() => setViewing(i === live.pictures.length - 1 ? null : p.turn)} />;
                })}
              </div>
            )}
            <span className="muted small">
              {viewing ? <button className="link" onClick={() => setViewing(null)}>Earlier picture · back to live</button>
                : live.lastTurn && !live.lastTurn.drew && live.pictures.length ? "Picture not changed this turn"
                : live.pictures.length ? "Live" : ""}
            </span>
            <button className="chev-btn fold-btn" onClick={toggleFold} disabled={live.asks.length > 0 || live.permits.length > 0} title="Hide picture (Alt+P)">›</button>
          </footer>
        </section>
        )}
      </main>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(<App />);

// A box with a lid; the arrow points out of it for Unarchive.
function ArchiveIcon({ out }: { out?: boolean }) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="1.5" y="2.5" width="13" height="3.5" rx="0.8" />
      <path d="M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6" />
      {out ? <path d="M8 12V8.2M6.3 9.8 8 8.1l1.7 1.7" /> : <path d="M6.3 9h3.4" />}
    </svg>
  );
}
