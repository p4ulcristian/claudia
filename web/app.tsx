import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Messages, Composer } from "./chat";
import { PictureView } from "./picture";
import { AskPanel, PermitPanel } from "./panels";
import { TimelineView } from "./timeline";
import { RightBar } from "./rightbar";
import { clip, tilde } from "./changes";
import { ActivityPill } from "./activity";
import { Meters, Pickers, RailMeters } from "./usage";
import { ColorPicker, chatColorVar } from "./colors";
import { ChatMenu, type MenuChat } from "./chatmenu";
import { CHAT_COLORS, PASSIVE } from "../src/colors";
import { GithubPrompt } from "./github-prompt";
import { TipBubble, tipProps } from "./tip";
import { savePicture } from "./snapshot";
import { FormsGallery } from "./gallery";
import { Brand, NoProfile, ProfileTabs, brandName, Settings as SettingsDialog, useClaudeToken, type Me } from "./settings";
import type { Ask, ChangeMeta, ChatEvent, ChatRow, SearchFound, Context, Item, Meta, Permit, Picture, Scene, Settings, Task } from "./types";
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

// /new?text=… (e.g. Eye on Rainbow's "Open in Claudia"): a new chat of your own with the text in
// the message box, not sent. Your own chats, so another profile's read-only view is left first;
// the address goes back to / so a reload doesn't fill the box again.
const startText = (() => {
  if (location.pathname !== "/new") return "";
  const text = new URLSearchParams(location.search).get("text") ?? "";
  document.cookie = "claudia_view=; path=/; max-age=0; samesite=lax";
  history.replaceState(null, "", "/");
  return text;
})();

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

// Events held while a chat opened can repeat items its history already had: those are skipped.
const itemKey = (it: Item) => (it.k === "user" ? `u:${it.uuid}` : `${it.k}:${it.id}`);
const fresher = (have: Item[], add: Item[]) => {
  const seen = new Set(have.map(itemKey));
  return add.filter((it) => !seen.has(itemKey(it)));
};

function apply(l: Live, ev: ChatEvent): Live {
  switch (ev.t) {
    case "user": return { ...l, items: [...l.items, ...fresher(l.items, [ev.item])], error: undefined, lastTurn: undefined };
    // A queued message joins the chat where Claude took it in: at the end, as things stand now.
    case "queue": {
      const it = l.items.find((i): i is Extract<Item, { k: "user" }> => i.k === "user" && i.uuid === ev.uuid);
      if (!it?.queued) return l;
      const rest = l.items.filter((i) => i !== it);
      return { ...l, items: ev.state === "started" ? [...rest, { ...it, queued: undefined }] : rest };
    }
    case "delta": return { ...l, partial: l.partial + ev.text };
    case "items": return { ...l, partial: "", items: [...l.items, ...fresher(l.items, ev.items)] };
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
// Below 1100px the chat list is a drawer; below 680px (phones) the picture is a bottom sheet too.
function useMedia(q: string) {
  const [narrow, setNarrow] = useState(() => matchMedia(q).matches);
  useEffect(() => {
    const m = matchMedia(q);
    const on = () => setNarrow(m.matches);
    m.addEventListener("change", on);
    return () => m.removeEventListener("change", on);
  }, [q]);
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
  const narrow = useMedia("(max-width: 1099px)");
  const phone = useMedia("(max-width: 679px)");
  // Phone: the picture lives in a sheet that slides up from the bottom; sheetAt = when it was last closed.
  const [sheet, setSheet] = useState(false);
  const [sheetAt, setSheetAt] = useState(() => Date.now());
  const closeSheet = useCallback(() => { setSheet(false); setSheetAt(Date.now()); }, []);
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
  // The right half shows Claude's picture, or the timeline of what the chat and its agents did.
  const [rightView, setRightViewState] = useState<"picture" | "timeline">(() => (store.get("right") === "timeline" ? "timeline" : "picture"));
  // Pictures drawn after this, while the timeline shows, get a dot on the Picture tab.
  const [timelineAt, setTimelineAt] = useState(() => Date.now());
  const setRightView = useCallback((v: "picture" | "timeline") => { store.set("right", v); setRightViewState(v); setTimelineAt(Date.now()); }, []);
  const [editing, setEditing] = useState(false);
  // The mouse is near the bottom of the right half: the bar there shows.
  const [nearBar, setNearBar] = useState(false);
  // A question fills the window; peeking hides it to look at the picture, and a new question shows again.
  const [peek, setPeek] = useState(false);
  const peekAsk = useCallback(() => setPeek(true), []);
  const askId = live.asks[0]?.id;
  useEffect(() => setPeek(false), [askId]);
  const [meta, setMeta] = useState<Meta>({ models: [], commands: [], limits: [], account: "", accountLabel: null, defaultModel: "", at: 0 });
  // This Claudia's name, and who is signed in with which profile.
  const [me, setMe] = useState<Me | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [formsOpen, setFormsOpen] = useState(false);
  const closeForms = useCallback(() => setFormsOpen(false), []);
  const closeSettings = useCallback(() => setSettingsOpen(false), []);
  useEffect(() => {
    fetch("/api/me").then((r) => r.json()).then(setMe, () => setMe(null));
  }, []);
  const currentRef = useRef(current);
  currentRef.current = current;

  // Chats whose turn ended while another chat was open: a "done" badge until opened (kept across reloads).
  const [done, setDone] = useState<Set<string>>(() => new Set(JSON.parse(store.get("done") || "[]")));
  const wasRunning = useRef(new Map<string, boolean>());
  const changeDone = useCallback((f: (d: Set<string>) => void) => setDone((old) => {
    const d = new Set(old);
    f(d);
    store.set("done", JSON.stringify([...d]));
    return d;
  }), []);
  useEffect(() => changeDone((d) => d.delete(current)), [current]);

  const loadChats = useCallback(async () => {
    const r = await fetch(`/api/chats?all=${all ? 1 : 0}&archived=${archivedView ? 1 : 0}`);
    if (!r.ok) return;
    const rows: ChatRow[] = await r.json();
    for (const c of rows) known.current.set(c.id, c);
    const ended = rows.filter((c) => wasRunning.current.get(c.id) && !c.running && c.id !== currentRef.current);
    for (const c of rows) wasRunning.current.set(c.id, c.running);
    if (ended.length) changeDone((d) => ended.forEach((c) => d.add(c.id)));
    setChats(rows);
  }, [all, archivedView]);
  // Search: two letters or more search what was said in the chats (the runner's index), not only titles.
  const [found, setFound] = useState<SearchFound[] | null>(null);
  useEffect(() => {
    const text = search.trim();
    if (text.length < 2) { setFound(null); return; }
    const ctl = new AbortController();
    const t = setTimeout(() => {
      fetch(`/api/search?q=${encodeURIComponent(text)}&all=${all ? 1 : 0}&archived=${archivedView ? 1 : 0}`, { signal: ctl.signal })
        .then((r) => (r.ok ? r.json() : null))
        .then((f) => f && setFound(f))
        .catch(() => {});
    }, 180);
    return () => { clearTimeout(t); ctl.abort(); };
  }, [search, all, archivedView]);
  // A hit opens its chat and scrolls to the message once it's on the page.
  const [jumpTo, setJumpTo] = useState<string | null>(null);
  useEffect(() => {
    if (!jumpTo) return;
    let tries = 0;
    const iv = setInterval(() => {
      const el = document.querySelector<HTMLElement>(`[data-at="${CSS.escape(jumpTo)}"]`);
      if (el || ++tries > 40) {
        clearInterval(iv);
        setJumpTo(null);
        if (!el) return;
        el.scrollIntoView({ block: "center", behavior: "smooth" });
        el.classList.remove("found-flash");
        void el.offsetWidth;
        el.classList.add("found-flash");
      }
    }, 120);
    return () => clearInterval(iv);
  }, [jumpTo]);
  const openHit = (id: string, uuid: string | null) => {
    if (id !== current) open(id);
    if (uuid) setJumpTo(uuid);
  };

  // Someone else's chats are read only: no new chat there, so open their latest one (the last) instead.
  const readOnly = me?.readOnly ?? false;
  const landed = useRef(false);
  useEffect(() => {
    if (!readOnly || landed.current || idFromPath() || !chats.length) return;
    landed.current = true;
    open(chats.at(-1)!.id);
  }, [readOnly, chats]);
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
  // A chat picked from the list nods "yes" and opens; the open one shakes "no, already here" and
  // isn't loaded again. (Web Animations, so React re-rendering the class doesn't cut them short.)
  const pick = (id: string, e: React.MouseEvent<HTMLElement>) => {
    const el = e.currentTarget;
    if (id === current) {
      el.animate([{ translate: "0 0" }, { translate: "-4px 0" }, { translate: "4px 0" }, { translate: "-3px 0" }, { translate: "2px 0" }, { translate: "0 0" }], { duration: 360, easing: "ease-in-out" });
      return;
    }
    el.animate([{ translate: "0 0" }, { translate: "0 4px" }, { translate: "0 -3px" }, { translate: "0 3px" }, { translate: "0 0" }], { duration: 420, easing: "ease-in-out" });
    open(id);
  };

  useEffect(() => {
    const pop = () => open(idFromPath() ?? crypto.randomUUID(), false);
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, [open]);

  const row = chats.find((c) => c.id === current) ?? known.current.get(current);
  const title = row?.title ?? (live.items.length ? "New chat" : "New chat");
  useEffect(() => { document.title = `${title} · ${brandName(me?.name)}`; }, [title, me]);

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
      if (!typing && rightView === "picture" && !e.altKey && !e.ctrlKey && !e.metaKey && (e.key === "ArrowLeft" || e.key === "ArrowRight")) {
        e.preventDefault();
        showPicture(picIndex + (e.key === "ArrowLeft" ? -1 : 1));
      }
      if (e.altKey && e.key.toLowerCase() === "n") { e.preventDefault(); newChat(); }
      if (e.altKey && e.key.toLowerCase() === "p") { e.preventDefault(); toggleFold(); }
      if (e.altKey && e.key.toLowerCase() === "t") { e.preventDefault(); setRightView(rightView === "timeline" ? "picture" : "timeline"); }
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
  // Archive hides the chat (the open one moves on to the next); in the archived view it brings it back.
  const archiveChat = async (id: string, archived: boolean) => {
    await fetch(`/api/chats/${id}`, { method: "PATCH", body: JSON.stringify({ archived }) });
    if (!archived || id !== current) return;
    const next = chats.find((c) => c.id !== current);
    if (next) open(next.id);
    else newChat();
  };
  const setArchived = (archived: boolean) => archiveChat(current, archived);
  // The right-click menu on a chat (rail circle or list row).
  const [menu, setMenu] = useState<{ chat: MenuChat; x: number; y: number } | null>(null);
  const closeMenu = useCallback(() => setMenu(null), []);
  const menuFor = (c: { id: string; title: string; color: string | null }) => (e: React.MouseEvent) => {
    if (readOnly) return;
    e.preventDefault();
    setMenu({ chat: { id: c.id, title: c.title, color: c.color, archived: archivedView }, x: e.clientX, y: e.clientY });
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
  // The open chat stays in view in the list and the rail (a new chat sits at the end).
  // (Only the list's own scroll moves: scrollIntoView would also shift the hidden sidebar's frame.)
  useEffect(() => {
    for (const el of document.querySelectorAll<HTMLElement>(".sidebar nav .chat-row.active, .rail-dots .rail-dot.on")) {
      const list = el.parentElement!;
      const r = el.getBoundingClientRect(), l = list.getBoundingClientRect();
      if (r.bottom > l.bottom) list.scrollTop += r.bottom - l.bottom + 8;
      else if (r.top < l.top) list.scrollTop -= l.top - r.top + 8;
    }
  }, [current, chats.length, live.items.length > 0]);
  const hasPicture = shown.scene.blocks ? shown.scene.blocks.length > 0 : shown.scene.nodes.length + shown.scene.cards.length > 0;

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
  // Background agents and this turn's changes, as a pill: over the picture, or in the timeline's header.
  const activity = (
    <ActivityPill
      chat={current}
      tasks={live.tasks}
      subs={live.subs}
      changes={shownChanges}
      changesLabel={changesLabel}
      folder={live.settings.folder}
      onStop={(task) => send({ t: "stop_task", chat: current, task })}
      onTimeline={() => setRightView("timeline")}
    />
  );

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

  // A question opens the right half while it's pending, so peeking shows the picture.
  const folded = foldedAt > 0 && !live.asks.length;
  const unseen = folded && (live.pictures.at(-1)?.at ?? 0) > foldedAt;
  const fold = useFoldMotion(folded && !phone, split, phone);
  const sheetUnseen = phone && !sheet && (live.pictures.at(-1)?.at ?? 0) > sheetAt;
  const pictureBehind = rightView === "timeline" && (live.pictures.at(-1)?.at ?? 0) > timelineAt;

  const q = search.trim().toLowerCase();
  const list = q ? chats.filter((c) => c.title.toLowerCase().includes(q)) : chats;
  const tags = useMemo(() => projectTags(chats.map((c) => c.project)), [chats]);
  // The rail: passive chats last, in their own order.
  const railList = [...list.filter((c) => c.color !== PASSIVE), ...list.filter((c) => c.color === PASSIVE)];
  const railBox = useRef<HTMLDivElement>(null);
  useSlide(railBox, `${showSidebar ? "" : "rail:"}${railList.map((c) => c.id).join(",")}`);

  if (me && !me.profile) return <NoProfile me={me} />;

  return (
    <div className={`app ${showSidebar ? "" : "no-sidebar"} ${narrow ? "narrow" : ""} ${readOnly ? "read-only" : ""}`}>
      <TipBubble />
      {me?.profile && !me.readOnly && !settingsOpen && <GithubPrompt profile={me.profile} />}
      {me && settingsOpen && <SettingsDialog me={me} onMe={setMe} close={closeSettings} />}
      {formsOpen && <FormsGallery close={closeForms} />}
      {menu && (
        <ChatMenu
          {...menu}
          close={closeMenu}
          onColor={(color) => send({ t: "settings", chat: menu.chat.id, color })}
          onWake={() => send({ t: "wake", chat: menu.chat.id })}
          onRename={(title) => void fetch(`/api/chats/${menu.chat.id}`, { method: "PATCH", body: JSON.stringify({ title }) })}
          onArchive={(archived) => void archiveChat(menu.chat.id, archived)}
        />
      )}
      {narrow && drawer && <div className="backdrop" onClick={() => setDrawer(false)} />}
      {!readOnly && (live.asks.length > 0 || live.permits.length > 0) && (
        <>
          <div className="ask-modal" hidden={peek} role="dialog" aria-modal="true" aria-label="Claude asks">
            {live.permits.map((p) => <PermitPanel key={p.id} permit={p} onPermit={(allow) => send({ t: "permit", chat: current, id: p.id, allow })} />)}
            {live.asks.map((a) => <AskPanel key={a.id} ask={a} active={!peek} onPeek={peekAsk} onAnswer={(answers) => send({ t: "answer", chat: current, id: a.id, answers })} />)}
          </div>
          {peek && <button className="ask-return" onClick={() => setPeek(false)}>Claude asks · answer</button>}
        </>
      )}
      <button className="icon menu-toggle" onClick={toggleSidebar} title={showSidebar ? "Hide chats (Ctrl+B)" : "Show chats (Ctrl+B)"} aria-expanded={showSidebar}>
        <svg className="burger" viewBox="0 0 18 18" aria-hidden="true"><path d="M3 4.6h12M3.4 9h11.3M3 13.4h12" /></svg>
      </button>
      <div className="sidebar-slot">
        {!showSidebar && (
          <nav className="rail" aria-label="Chats">
            <div className="rail-dots" ref={railBox}>
              {!readOnly && <button className="rail-dot add" onClick={newChat} title="New chat (Alt+N)">+</button>}
              {railList.map((c, i) => (
                <button
                  key={c.id}
                  data-slide={c.id}
                  className={`rail-dot ${c.id === current ? "on" : ""} ${c.color === PASSIVE ? `passive ${railList[i - 1]?.color !== PASSIVE ? "first" : ""} ${i === railList.length - 1 ? "last" : ""}` : ""}`}
                  style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}
                  onClick={(e) => pick(c.id, e)}
                  onContextMenu={menuFor(c)}
                  {...tipProps(c.project ? `${c.title} · ${c.project}` : c.title, chatColorVar(c.color))}
                  aria-label={c.title}
                >
                  {(c.title.trim()[0] ?? "?").toUpperCase()}
                  <StateBadge state={c.waiting ? "waiting" : c.running ? "thinking" : done.has(c.id) ? "done" : null} />
                  <AgentMoons n={c.agents} />
                  {c.project && <ProjectTag name={c.project} tag={tags.get(c.project) ?? ""} logo={c.logo} />}
                </button>
              ))}
            </div>
            <button className="rail-forms" onClick={() => setFormsOpen(true)} {...tipProps("Forms: everything Claude can draw, and your templates")} aria-label="Forms">
              <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="3" width="6" height="6" rx="1.5" /><circle cx="14" cy="6" r="3" /><path d="M3 15h6M11 12.5l3 3 3-3" /></svg>
            </button>
            <RailMeters meta={meta} context={live.context} onCompact={readOnly || live.running ? undefined : () => onSend("/compact", [])} />
          </nav>
        )}
        <aside className="sidebar" aria-hidden={!showSidebar} inert={!showSidebar}>
          <Brand me={me} token={meta.accountLabel ?? me?.claudeToken?.name ?? null} />
          {me && <ProfileTabs me={me} />}
          {!readOnly && <button className="new" onClick={newChat} title="New chat (Alt+N)">New chat</button>}
          <label className="search-box"><input className="search" placeholder="Search chats" value={search} onChange={(e) => setSearch(e.target.value)} /></label>
          <nav>
            {found && (
              <>
                {found.map((f) => {
                  const c = known.current.get(f.id);
                  if (!c) return null;
                  return (
                    <div key={f.id} className="found" style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}>
                      <button className={`chat-row ${c.id === current ? "active" : ""} ${c.color ? "colored" : ""}`} onClick={() => openHit(c.id, null)} onContextMenu={menuFor(c)}>
                        <span className="t">{f.title ? <Marked text={c.title} words={search} /> : c.title}</span>
                        <span className="when">{f.count > 0 ? `${f.count} hit${f.count === 1 ? "" : "s"}` : ago(c.at)}</span>
                      </button>
                      {f.hits.map((h) => (
                        <button key={h.uuid} className={`found-hit ${h.role}`} onClick={() => openHit(c.id, h.uuid)}>
                          <span className="who">{h.role === "user" ? "you" : "claude"}</span>
                          <span className="snip">{h.snippet.split(/(\u0001[^\u0002]*\u0002)/).map((part, i) => part.startsWith("\u0001") ? <mark key={i}>{part.slice(1, -1)}</mark> : part)}</span>
                        </button>
                      ))}
                    </div>
                  );
                })}
                {!found.length && <p className="muted small">Nothing said matches.</p>}
              </>
            )}
            {!found && list.map((c) => (
              <button
                key={c.id}
                className={`chat-row ${c.id === current ? "active" : ""} ${c.color ? "colored" : ""}`}
                style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}
                onClick={(e) => pick(c.id, e)}
                onContextMenu={menuFor(c)}
                {...tipProps(c.title, chatColorVar(c.color), true)}
              >
                {c.running && <span className="run-dot" title="Working" />}
                <span className="t">{c.title}</span>
                <span className="when">{ago(c.at)}</span>
              </button>
            ))}
            {!found && !row && live.items.length > 0 && <div className="chat-row active"><span className="t">New chat</span></div>}
            {!found && !list.length && <p className="muted small">{q ? "No match." : archivedView ? "Nothing archived." : "No chats yet."}</p>}
          </nav>
          <button className="forms-link" onClick={() => setFormsOpen(true)} title="Everything Claude can draw, and your saved templates">
            <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="3" width="6" height="6" rx="1.5" /><circle cx="14" cy="6" r="3" /><path d="M3 15h6M11 12.5l3 3 3-3" /></svg>
            Forms
          </button>
          <Meters
            meta={meta}
            context={live.context}
            saved={me?.claudeTokens.map((t) => t.name) ?? []}
            onSwitch={async (name) => {
              const next = await useClaudeToken(name);
              if (next) setMe((m) => m && { ...m, ...next });
            }}
          />
          <div className="side-foot">
            <button className={`archived-toggle ${archivedView ? "on" : ""}`} onClick={() => setArchivedView((v) => !v)}>
              <ArchiveIcon /> {archivedView ? "Back to chats" : "Archived"}
            </button>
            <label className="toggle">
              <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
              Show all ~/Work sessions
            </label>
            {!readOnly && <button className="archived-toggle" onClick={() => setSettingsOpen(true)} title={me?.email ? `Signed in as ${me.email}` : undefined}>
              ⚙ Settings
            </button>}
          </div>
        </aside>
      </div>
      <main
        ref={fold.ref}
        className={`split ${fold.shown ? "folded" : ""} ${fold.cls}`}
        style={{
          gridTemplateColumns: fold.cols ?? (fold.shown ? `minmax(300px, 1fr) 6px ${EDGE}px` : `minmax(300px, ${Math.round(split * 1000)}fr) 6px minmax(280px, ${Math.round((1 - split) * 1000)}fr)`),
          ...(fold.width !== null ? { "--fold-w": `${fold.width}px` } : {}),
        } as React.CSSProperties}
      >
        <section className="left">
          <header className={`chat-head ${live.settings.color ? "colored" : ""}`}>
            {!readOnly && <ColorPicker color={live.settings.color} onPick={(color) => send({ t: "settings", chat: current, color })} />}
            {editing ? (
              <input
                className="title-edit"
                autoFocus
                defaultValue={title}
                onBlur={(e) => rename(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") rename(e.currentTarget.value); if (e.key === "Escape") setEditing(false); }}
              />
            ) : (
              // The title renames itself: click it (a pencil shows on hover).
              <h1
                className={row && !readOnly ? "renamable" : ""}
                onClick={() => row && !readOnly && setEditing(true)}
                title={row && !readOnly ? "Click to rename" : ""}
              >
                <span className="t">{title}</span>
                {row && !readOnly && <span className="pencil" aria-hidden="true">✎</span>}
              </h1>
            )}
            {live.settings.folder && <span className="folder-chip" title={`Working folder: ${live.settings.folder}`}>{clip(tilde(live.settings.folder), 28)}</span>}
            {activity}
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
          {readOnly ? (
            <div className="read-only-note">{me?.viewing ? `${me.viewing[0].toUpperCase()}${me.viewing.slice(1)}'s chat` : "Someone else's chat"} · read only</div>
          ) : <Composer
            running={live.running}
            asking={live.asks.length > 0}
            onSend={onSend}
            onStop={onStop}
            focusKey={current}
            initialText={startText}
            commands={meta.commands}
            actions={<>
              <SaveButton title={shown.scene.title || title} disabled={fold.shown || !hasPicture} />
              {row && !readOnly && (
                row.archived
                  ? <button className="icon archive" onClick={() => setArchived(false)} title="Unarchive"><ArchiveIcon out /></button>
                  : <button className="icon archive" onClick={() => setArchived(true)} title="Archive"><ArchiveIcon /></button>
              )}
            </>}
            tools={<Pickers meta={meta} settings={live.settings} model={live.model} onSettings={(next) => send({ t: "settings", chat: current, ...next })} />}
          />}
        </section>
        <div className="divider" onPointerDown={fold.shown ? undefined : dragSplit} />
        {phone && sheet && <div className="sheet-backdrop" onClick={closeSheet} />}
        {phone && (
          <button className={`sheet-tab ${sheetUnseen ? "unseen" : ""}`} onClick={() => setSheet(true)} aria-label="Show picture">
            <span className="grab-line" />
            Picture
          </button>
        )}
        {fold.shown ? (
          <section className="right fold-edge">
            <button className={`unfold ${unseen ? "unseen" : ""}`} onClick={toggleFold} title={unseen ? "New picture · show (Alt+P)" : "Show picture (Alt+P)"} aria-label="Show picture">
              <span className="edge-line" />
            </button>
          </section>
        ) : (
        <section
          className={`right ${phone ? "sheet" : ""} ${phone && sheet ? "open" : ""}`}
          style={fold.rightWidth ? { width: fold.rightWidth } : undefined}
          onMouseMove={(e) => setNearBar(e.clientY > e.currentTarget.getBoundingClientRect().bottom - 96)}
          onMouseLeave={() => setNearBar(false)}
        >
          {phone && <SheetGrab onClose={closeSheet} />}
          {rightView === "timeline" ? (
          <div className="right-tl">
            <TimelineView chat={current} live={live.running || live.tasks.some((t) => t.status === "running")} />
          </div>
          ) : (
          <div className="right-scroll">
          <div className={`stage ${viewing ? "past" : ""}`}>
            <PictureView scene={shown.scene} changed={shown.changed} seq={shown.seq} chat={viewing || readOnly ? undefined : current} />
          </div>
          </div>
          )}
          {/* The bar shows only while the mouse is near the bottom (or an earlier picture is shown). */}
          <div className={`rbar-zone ${nearBar ? "show" : ""} ${viewing ? "pinned" : ""}`}>
          <RightBar
            view={rightView}
            onView={setRightView}
            pictureBehind={pictureBehind}
            pictures={live.pictures}
            index={picIndex}
            viewing={!!viewing}
            unchanged={!!live.lastTurn && !live.lastTurn.drew}
            onPick={showPicture}
            onLive={() => setViewing(null)}
            onFold={phone ? closeSheet : toggleFold}
            foldDisabled={live.asks.length > 0}
          />
          </div>
        </section>
        )}
      </main>
    </div>
  );
}

// iOS Safari ignores user-scalable=no: block pinch zoom of the page itself (two-finger gestures).
addEventListener("gesturestart", (e) => e.preventDefault());
addEventListener("touchmove", (e: TouchEvent) => { if (e.touches.length > 1) e.preventDefault(); }, { passive: false });

createRoot(document.getElementById("root")!).render(<App />);

// A box with a lid; the arrow points out of it for Unarchive.
// A chalked badge on a chat's circle: waiting on your answer, working, or finished since you looked.
const BADGES = {
  waiting: { mark: "?", label: "Waiting for you" },
  thinking: { mark: "", label: "Working" },
  done: { mark: "✓", label: "Done" },
};

function StateBadge({ state }: { state: keyof typeof BADGES | null }) {
  if (!state) return null;
  const b = BADGES[state];
  return (
    <span className={`state-badge ${state}`} title={b.label}>
      {state === "thinking" ? <><i /><i /><i /></> : b.mark}
    </span>
  );
}

// Subagents still working for a chat: small chalk circles around its circle's left and bottom, one each (up to 5).
// The words of a search marked in a title.
function Marked({ text, words }: { text: string; words: string }) {
  const ws = words.toLowerCase().match(/[\p{L}\p{N}_]+/gu) ?? [];
  if (!ws.length) return <>{text}</>;
  const re = new RegExp(`(${ws.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})`, "gi");
  return <>{text.split(re).map((p, i) => (i % 2 ? <mark key={i}>{p}</mark> : p))}</>;
}

// Circles slide to their new place when the order changes (a chat made passive sinks, a woken one
// rises) instead of jumping; a new one grows in. Each [data-slide] child is measured after every
// change and animated from where it was.
function useSlide(box: React.RefObject<HTMLElement | null>, order: string) {
  const last = useRef<Map<string, number> | null>(null);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) { last.current = null; return; }
    const top = el.getBoundingClientRect().top - el.scrollTop;
    const now = new Map<string, number>();
    for (const d of el.querySelectorAll<HTMLElement>("[data-slide]")) now.set(d.dataset.slide!, d.getBoundingClientRect().top - top);
    const before = last.current;
    last.current = now;
    if (!before || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    for (const d of el.querySelectorAll<HTMLElement>("[data-slide]")) {
      const was = before.get(d.dataset.slide!);
      const is = now.get(d.dataset.slide!)!;
      if (was === undefined) d.animate([{ opacity: 0, transform: "scale(0.4)" }, { opacity: 1, transform: "none" }], { duration: 260, easing: "cubic-bezier(.2,.8,.3,1.2)" });
      else if (Math.abs(was - is) > 1) d.animate([{ transform: `translateY(${was - is}px)` }, { transform: "none" }], { duration: 420, easing: "cubic-bezier(.3,.7,.2,1)" });
    }
  }, [order]);
}

// Short tags for projects, unique among the ones in the list: initials of a dashed name
// (eye-on-rainbow → eor), else the first letters, three when two projects would share two.
function projectTags(names: (string | null)[]): Map<string, string> {
  const all = [...new Set(names.filter((n): n is string => !!n))];
  const tag = (n: string, len: number) => {
    const parts = n.split(/[-_. ]+/).filter(Boolean);
    return (parts.length > 1 ? parts.map((p) => p[0]).join("").slice(0, 3) : n.slice(0, len)).toLowerCase();
  };
  const out = new Map<string, string>();
  for (const n of all) {
    const clash = all.some((m) => m !== n && tag(m, 2) === tag(n, 2));
    out.set(n, tag(n, clash ? 3 : 2));
  }
  return out;
}

// The project on a chat's circle: its logo when the repo has one, else its short name, tinted the
// same colour wherever that project shows.
function ProjectTag({ name, tag, logo }: { name: string; tag: string; logo: boolean }) {
  const [broken, setBroken] = useState(false);
  if (logo && !broken)
    return (
      <span className="project-tag logo">
        <img src={`/api/projects/${encodeURIComponent(name)}/logo`} alt="" onError={() => setBroken(true)} />
      </span>
    );
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const tints = CHAT_COLORS.filter((c) => c !== PASSIVE);
  return <span className="project-tag" style={{ "--tag": `var(--c-${tints[h % tints.length]})` } as React.CSSProperties}>{tag}</span>;
}

function AgentMoons({ n }: { n: number }) {
  if (!n) return null;
  const shown = Math.min(n, 5);
  return (
    <span className="moons" title={`${n} agent${n > 1 ? "s" : ""} working`}>
      {Array.from({ length: shown }, (_, i) => {
        const a = ((160 + i * 32) * Math.PI) / 180;
        return <i key={i} style={{ left: `calc(50% + ${Math.cos(a) * 21}px)`, top: `calc(50% - ${Math.sin(a) * 21}px)`, animationDelay: `${i * 0.25}s` }} />;
      })}
    </span>
  );
}

// The top of the picture sheet on phones: tap it, or drag it down, to close the sheet.
function SheetGrab({ onClose }: { onClose: () => void }) {
  const drag = (e: React.PointerEvent<HTMLButtonElement>) => {
    const sheet = e.currentTarget.parentElement!;
    const y0 = e.clientY;
    let dy = 0;
    e.currentTarget.setPointerCapture(e.pointerId);
    const move = (m: PointerEvent) => {
      dy = Math.max(0, m.clientY - y0);
      sheet.style.transition = "none";
      sheet.style.transform = `translateY(${dy}px)`;
    };
    const up = () => {
      removeEventListener("pointermove", move);
      removeEventListener("pointerup", up);
      sheet.style.transition = "";
      sheet.style.transform = "";
      if (dy > 80 || dy < 6) onClose(); // a long drag down, or a tap
    };
    addEventListener("pointermove", move);
    addEventListener("pointerup", up);
  };
  return (
    <button className="sheet-grab" onPointerDown={drag} aria-label="Close picture">
      <span className="grab-line" />
    </button>
  );
}

// Folding the right half slides it shut instead of jumping: its column width moves in px from where
// it is to where it'll be (through --fold-w, a registered length, so it transitions), the pane keeps
// its width meanwhile (so the picture doesn't re-lay out every frame) and fades; only then the edge
// replaces it (or, unfolding, the edge goes first).
const FOLD_MS = 320;
// The folded right half: a thin chalk edge (px).
const EDGE = 16;
function useFoldMotion(folded: boolean, split: number, narrow: boolean) {
  const ref = useRef<HTMLElement>(null);
  const [shown, setShown] = useState(folded);
  const [width, setWidth] = useState<number | null>(null);
  const [rightWidth, setRightWidth] = useState<number | null>(null);
  // dir: which way it moves (set with the start width); go: the transition is on (a frame later).
  const [dir, setDir] = useState<"fold" | "unfold" | null>(null);
  const [go, setGo] = useState(false);
  useLayoutEffect(() => {
    if (folded === shown) {
      // Toggled back before the motion ended: settle where it is.
      setWidth(null);
      setRightWidth(null);
      setDir(null);
      setGo(false);
      return;
    }
    const main = ref.current;
    if (!main || narrow || matchMedia("(prefers-reduced-motion: reduce)").matches) return setShown(folded);
    const total = main.clientWidth;
    const open = folded
      ? total - 6 - (main.firstElementChild as HTMLElement).getBoundingClientRect().width
      : total - 6 - Math.min(total - 6 - 280, Math.max(300, (total - 6) * split));
    setRightWidth(open);
    setDir(folded ? "fold" : "unfold");
    setGo(false);
    setWidth(folded ? open : EDGE);
    if (!folded) setShown(false);
    // Two frames: the start width is laid out before the transition and the end width are set
    // (with the transition on from the start, --fold-w would run from its initial 0px).
    let raf = requestAnimationFrame(() => (raf = requestAnimationFrame(() => {
      setGo(true);
      setWidth(folded ? EDGE : open);
    })));
    const done = setTimeout(() => {
      setShown(folded);
      setWidth(null);
      setRightWidth(null);
      setDir(null);
      setGo(false);
    }, FOLD_MS);
    return () => {
      cancelAnimationFrame(raf);
      clearTimeout(done);
    };
  }, [folded]);
  const cols = width === null ? null : "minmax(0, 1fr) 6px var(--fold-w)";
  const cls = dir ? `moving to-${dir} ${go ? "go" : ""}` : "";
  return { ref, shown, cols, width, rightWidth, cls };
}

// Save the picture shown on the right as a PNG.
function SaveButton({ title, disabled }: { title: string; disabled: boolean }) {
  const [busy, setBusy] = useState(false);
  // A short note after saving: where it went (the browser saves to its downloads folder silently).
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => {
    if (!note) return;
    const t = setTimeout(() => setNote(null), 4000);
    return () => clearTimeout(t);
  }, [note]);
  const save = async () => {
    const el = document.querySelector<HTMLElement>(".right .stage .picture");
    if (!el || busy) return;
    setBusy(true);
    try {
      const { file, copied } = await savePicture(el, title);
      setNote(`Saved to Downloads: ${file}${copied ? " · also copied, paste it anywhere" : ""}`);
    } catch (e) {
      console.error("save picture", e);
      setNote("Couldn't save the picture");
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
    {note && <span className="save-note" role="status">{note}</span>}
    <button className={`icon save-pic ${busy ? "busy" : ""}`} onClick={save} disabled={disabled || busy} title={busy ? "Saving the picture…" : "Save the picture as an image"} aria-label="Save the picture as an image">
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3.5 6.5h3l1.5-2h4l1.5 2h3v9h-13z" /><circle cx="10" cy="10.8" r="2.8" /></svg>
    </button>
    </>
  );
}

function ArchiveIcon({ out }: { out?: boolean }) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="1.5" y="2.5" width="13" height="3.5" rx="0.8" />
      <path d="M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6" />
      {out ? <path d="M8 12V8.2M6.3 9.8 8 8.1l1.7 1.7" /> : <path d="M6.3 9h3.4" />}
    </svg>
  );
}
