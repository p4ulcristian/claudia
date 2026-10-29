import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Messages, Composer } from "./chat";
import type { SentImage } from "./media";
import { PictureView } from "./picture";
import { PictureSend } from "./editor";
import { AskPanel, PermitPanel } from "./panels";
import { TimelineView } from "./timeline";
import { RightBar } from "./rightbar";
import { clip, tilde } from "./changes";
import { ActivityPill } from "./activity";
import { Meters, Pickers, RailMeters } from "./usage";
import { ColorPicker, chatColorVar } from "./colors";
import { ChatMenu, ContextMenu, type MenuChat } from "./chatmenu";
import { play } from "./sounds";
import { allowPopups, popup, popupsAllowed } from "./popups";
import { FormRow } from "./formrow";
import { CHAT_COLORS, PASSIVE } from "../src/colors";
import { GithubPrompt } from "./github-prompt";
import { TipBubble, tipProps } from "./tip";
import { bakeChalk } from "./chalk";
import { FormsGallery } from "./gallery";
import { Brand, NoProfile, ProfileTabs, brandName, Settings as SettingsDialog, useClaudeToken, type Me } from "./settings";
import { ApiCtx, PAGE_API, apiFor, type Api } from "./api";
import { AdminDialog, HubEmpty, NewChatServer, PeopleTabs, ServerFilter, ServerMark, SettingsServers, serverMarks, type AdminTab, type HubMe, type ServerState, type ServerView } from "./hub";
import type { Ask, ChangeMeta, ChatEvent, ChatRow, SearchFound, Context, Item, Meta, Permit, Picture, Scene, Settings, Task } from "./types";
import "./style.css";
// The page as one layer the size of the visible area; only scrollers move under a finger.
import "./viewport";

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
  tasks: [], subs: {}, context: null, settings: { model: null, effort: null, folder: null, color: null, forms: {}, cols: 0, prevColor: null }, model: "", changes: [],
});

const CHAT_WIDTH = 400; // the chat column until dragged (px)
const NO_META: Meta = { models: [], commands: [], limits: [], account: "", accountLabel: null, defaultModel: "", at: 0 };

const store = {
  get: (k: string) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v); } catch {} },
};

// Where a chat lives: its server and its id. The server is "" when a server serves the page itself
// (single-server mode); in the hub, "" only while an old link's server is still being looked for.
type At = { server: string; id: string };
// A chat's key in the page's maps (its id alone on a server's own page, as before the hub).
const keyOf = (server: string, id: string) => (server ? `${server}/${id}` : id);
// /c/<chat> (a server's own page, or a hub link from before the hub), or /c/<server>/<chat> (the hub).
const fromPath = (): { server: string | null; id: string } | null => {
  const m = location.pathname.match(/^\/c\/(?:([a-z][a-z0-9-]{0,30})\/)?([0-9a-f-]{36})/);
  return m ? { server: m[1] ?? null, id: m[2]! } : null;
};
const pathOf = (at: At) => (at.server ? `/c/${at.server}/${at.id}` : `/c/${at.id}`);

// /new?text=… (e.g. Eye on Rainbow's "Open in Claudia"): a new chat of your own with the text in
// the message box, not sent. Your own chats, so another profile's read-only view is left first;
// the address goes back to / so a reload doesn't fill the box again.
const startText = (() => {
  if (location.pathname !== "/new") return "";
  const text = new URLSearchParams(location.search).get("text") ?? "";
  document.cookie = "claudia_view=; path=/; max-age=0; samesite=lax";
  store.set("hub.view", "");
  history.replaceState(null, "", "/");
  return text;
})();

// A chat socket that comes back by itself and resends what was queued. ready = the server behind
// it answered (its first message), not only the socket opening: in the hub the socket opens at the
// hub and closes again when the server is off.
type ConnEvents = { msg: (m: any) => void; open: () => void; ready: () => void; down: () => void };
class Conn {
  private ws: WebSocket | null = null;
  private queue: string[] = [];
  private dead = false;
  private retry = 300;
  ready = false;
  constructor(readonly url: string, private on: ConnEvents) {
    this.connect();
  }
  private connect() {
    const s = new WebSocket(this.url);
    this.ws = s;
    s.onopen = () => {
      this.on.open();
      for (const q of this.queue.splice(0)) s.send(q);
    };
    s.onmessage = (e) => {
      if (!this.ready) {
        this.ready = true;
        this.retry = 300;
        this.on.ready();
      }
      this.on.msg(JSON.parse(e.data));
    };
    s.onclose = () => {
      if (this.dead) return;
      this.ready = false;
      this.on.down();
      setTimeout(() => !this.dead && this.connect(), (this.retry = Math.min(this.retry * 2, 5000)));
    };
  }
  send(m: unknown) {
    const s = JSON.stringify(m);
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(s);
    else this.queue.push(s);
  }
  close() {
    this.dead = true;
    this.ws?.close();
  }
}

// A chat in the merged list: which server it is on, its key, and whether that server is off.
type Row = ChatRow & { server: string; key: string; off: boolean };

// Several servers' lists as one, in the order the chats were started; each server's own order is kept.
function mergeByStart(lists: Row[][]): Row[] {
  const startOf = (c: Row) => c.started ?? c.at;
  const at = lists.map(() => 0);
  const out: Row[] = [];
  for (;;) {
    let best = -1;
    for (let i = 0; i < lists.length; i++)
      if (at[i]! < lists[i]!.length && (best < 0 || startOf(lists[i]![at[i]!]!) < startOf(lists[best]![at[best]!]!))) best = i;
    if (best < 0) return out;
    out.push(lists[best]![at[best]!++]!);
  }
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

// Below 1100px the picture folds without motion; below 680px (phones) the chats are a stack of two
// screens and the picture a drawer from the right.
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


type Info = { me: Me | null; state: ServerState };

// Which mode the page is in: served by the hub (/hub/me answers), or by a server itself (it 404s:
// everything as before the hub, one server at "").
function Root() {
  const [mode, setMode] = useState<{ hub: HubMe | null } | null>(null);
  useEffect(() => {
    fetch("/hub/me")
      .then(async (r) => setMode({ hub: r.ok ? ((await r.json()) as HubMe) : null }))
      .catch(() => setMode({ hub: null }));
  }, []);
  return mode ? <App hubFirst={mode.hub} /> : null;
}

function App({ hubFirst }: { hubFirst: HubMe | null }) {
  const hubMode = hubFirst !== null;
  const [hub, setHub] = useState(hubFirst);
  const refreshHub = useCallback(() => {
    fetch("/hub/me").then((r) => (r.ok ? r.json() : null)).then((h) => h && setHub(h), () => {});
  }, []);
  // The servers this person may use (one, "", on a server's own page).
  const servers = useMemo(() => (hub ? hub.servers : [{ name: "", label: "" }]), [hub]);
  const serverNames = servers.map((s) => s.name).join(",");
  const marks = useMemo(() => serverMarks(servers), [servers]);
  const labelOf = (s: string) => servers.find((x) => x.name === s)?.label ?? s;
  // Marks on chats only when there is more than one server to tell apart.
  const showMarks = hubMode && servers.length > 1;

  // Each server: who you are there (its /api/me, kept for when it is off) and whether it is up.
  const [info, setInfo] = useState<Record<string, Info>>(() =>
    Object.fromEntries(servers.map((s) => [s.name, { me: hubMode ? (JSON.parse(store.get(`hub.me.${s.name}`) || "null") as Me | null) : null, state: "loading" as ServerState }])),
  );
  const patchInfo = useCallback((s: string, p: Partial<Info>) => setInfo((m) => ({ ...m, [s]: { ...(m[s] ?? { me: null, state: "loading" }), ...p } })), []);
  const loadMe = useCallback(async (s: string) => {
    if (!hubMode) {
      apiFor("").fetch("/api/me").then((r) => r.json()).then((me) => patchInfo("", { me, state: "up" }), () => patchInfo("", { me: null }));
      return;
    }
    const r = await apiFor(s).fetch("/api/me").catch(() => null);
    if (r?.ok) {
      const me = (await r.json()) as Me;
      store.set(`hub.me.${s}`, JSON.stringify(me));
      patchInfo(s, { me, state: "up" });
    } else patchInfo(s, { state: r?.status === 403 ? "noProfile" : "off" });
  }, [hubMode, patchInfo]);
  useEffect(() => { for (const s of servers) void loadMe(s.name); }, [serverNames]);

  // Whose chats (hub): null = your own everywhere; another person's are read only, from every server
  // that has that profile, and nothing from the others. (On a server's own page: the cookie.)
  const [person, setPerson] = useState<string | null>(() => (hubMode && store.get("hub.view")) || null);
  const viewOn = useCallback((s: string): { view: string | null; hidden: boolean } => {
    if (!hubMode || !person) return { view: null, hidden: false };
    const me = info[s]?.me;
    if (!me) return { view: null, hidden: true };
    if (me.profile === person) return { view: null, hidden: false };
    return me.profiles.includes(person) ? { view: person, hidden: false } : { view: null, hidden: true };
  }, [hubMode, person, info]);
  const apiOf = (s: string) => apiFor(s, viewOn(s).view);
  // The servers whose chats show: a profile there, and in the view.
  const shownServer = (s: string) => info[s]?.state !== "noProfile" && !viewOn(s).hidden;
  const viewSig = servers.map((s) => `${s.name}:${shownServer(s.name) ? viewOn(s.name).view ?? "" : "!"}`).join(",");
  const views: ServerView[] = servers.map((s) => ({ ...s, state: info[s.name]?.state ?? "loading", update: !!info[s.name]?.me && info[s.name]!.me!.api !== PAGE_API }));

  const [all, setAll] = useState(store.get("all") === "1");
  const [archivedView, setArchivedView] = useState(false);
  // Every chat seen in any list, so the open one keeps its title while the other list shows.
  const known = useRef(new Map<string, Row>());
  const [search, setSearch] = useState("");
  const narrow = useMedia("(max-width: 1099px)");
  const phone = useMedia("(max-width: 679px)");
  // Phone: two screens, the chat list and the open chat. Going from the list to a chat keeps the list
  // as the history entry behind it ({screen: "list"}; the chat's entry says list: true), so back (the
  // Chats button, Safari's swipe) returns to the list.
  const [screen, setScreen] = useState<"list" | "chat">(() => history.state?.screen ?? (fromPath() ? "chat" : "list"));
  const phoneRef = useRef(phone);
  phoneRef.current = phone;
  const screenRef = useRef(screen);
  screenRef.current = screen;
  // Phone: the picture is a drawer from the right, over the chat; picAt = when it was last closed.
  const [picOpen, setPicOpen] = useState(false);
  const [picAt, setPicAt] = useState(() => Date.now());
  const closePicture = useCallback(() => { setPicOpen(false); setPicAt(Date.now()); }, []);
  // The rail is always there, at every width. The full list (settings, archive, profiles) opens over
  // it as a drawer from the menu button and closes when a chat is picked or the backdrop clicked.
  const [drawer, setDrawer] = useState(false);
  // Search from the rail: the drawer opens with the search box focused, and folds back to the
  // rail when a chat is picked (or on Escape).
  const [railSearch, setRailSearch] = useState(false);
  const searchBox = useRef<HTMLInputElement>(null);
  const toggleSidebar = useCallback(() => {
    if (railSearch) return setRailSearch(false);
    setDrawer((d) => !d);
  }, [railSearch]);
  const showSidebar = phone ? screen === "list" : drawer || railSearch;
  const searchFromRail = () => { setRailSearch(true); requestAnimationFrame(() => searchBox.current?.focus()); };
  const endRailSearch = () => { if (railSearch) { setRailSearch(false); setSearch(""); } };

  // The server a new chat starts on: the last one used, if it can still be used.
  const usable = views.filter((v) => v.state !== "noProfile" && shownServer(v.name)).map((v) => v.name);
  const defaultServer = (() => {
    if (!hubMode) return "";
    const last = store.get("hub.server");
    return last && usable.includes(last) ? last : usable[0] ?? servers[0]?.name ?? "";
  })();
  // The open chat. An old hub link (/c/<chat>) waits until a server's list has the chat.
  const [first] = useState(() => {
    const p = fromPath();
    if (!hubMode) return { at: { server: "", id: p?.id ?? crypto.randomUUID() }, old: false };
    const may = (n: string | null) => !!n && servers.some((s) => s.name === n);
    if (p && may(p.server)) return { at: { server: p.server!, id: p.id }, old: false };
    if (p) return { at: { server: "", id: p.id }, old: true };
    const last = store.get("hub.server");
    return { at: { server: may(last) ? last! : servers[0]?.name ?? "", id: crypto.randomUUID() }, old: false };
  });
  const [current, setCurrent] = useState<At>(first.at);
  const [resolving, setResolving] = useState(first.old);
  const curKey = keyOf(current.server, current.id);
  const [live, setLive] = useState<Live>(() => fresh(current.id));
  const [viewing, setViewing] = useState<string | null>(null);
  // The chat column's width in px, not a share of the window: maximizing gives the room to the
  // picture. CHAT_WIDTH until dragged; a drag (wider too) is kept.
  const [chatW, setChatW] = useState(() => Number(store.get("chatW")) || CHAT_WIDTH);
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
  // Models, commands and usage: each server's own; the open chat's show.
  const [metas, setMetas] = useState<Record<string, Meta>>({});
  const meta = metas[current.server] ?? NO_META;
  // Who is signed in with which profile, on the open chat's server.
  const curInfo = info[current.server];
  const me = curInfo?.me ?? null;
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsServer, setSettingsServer] = useState("");
  const [formsOpen, setFormsOpen] = useState(false);
  const [adminTab, setAdminTab] = useState<AdminTab | null>(null);
  const closeForms = useCallback(() => setFormsOpen(false), []);
  const closeSettings = useCallback(() => setSettingsOpen(false), []);
  const closeAdmin = useCallback(() => { setAdminTab(null); refreshHub(); }, [refreshHub]);
  const currentRef = useRef(current);
  currentRef.current = current;
  // The open chat's server, for every feature (picture, files, git, timeline, changes, forms…).
  const openApi = apiOf(current.server);

  const wasRunning = useRef(new Map<string, boolean>());

  // Forms deleted in the Forms window (and opt-in forms this instance lacks): not offered in the row over the picture either.
  const [hiddenForms, setHiddenForms] = useState<string[]>([]);
  useEffect(() => { openApi.fetch("/api/prefs").then((r) => r.json()).then((p) => setHiddenForms([...(p.hiddenForms ?? []), ...(p.notHere ?? [])]), () => {}); }, [formsOpen, openApi]);
  const [sounds, setSounds] = useState(() => store.get("sounds") === "on");
  const soundsRef = useRef(sounds);
  soundsRef.current = sounds;
  const wasWaiting = useRef(new Map<string, boolean>());
  const toggleSounds = (on: boolean) => {
    setSounds(on);
    store.set("sounds", on ? "on" : "off");
    if (on) play("done", live.settings.color);
  };
  const [popups, setPopups] = useState(() => store.get("popups") === "on" && popupsAllowed());
  const popupsRef = useRef(popups);
  popupsRef.current = popups;
  const openRef = useRef<(at: At) => void>(() => {});
  const togglePopups = async (on: boolean) => {
    const allowed = on && (await allowPopups());
    setPopups(allowed);
    store.set("popups", allowed ? "on" : "off");
  };

  // Each server's chat list, under the view and toggles it was asked with (in the hub; one slot on a
  // server's own page, as before). The hub keeps the last list seen per server, shown while it is off.
  const [lists, setLists] = useState<Record<string, ChatRow[]>>({});
  const listId = (s: string) => (hubMode ? `${s}|${viewOn(s).view ?? ""}|${all ? 1 : 0}|${archivedView ? 1 : 0}` : "");
  const listIdRef = useRef(listId);
  listIdRef.current = listId;
  const asked = useRef(new Map<string, number>());
  const fetched = useRef(new Set<string>());
  const loadChats = useCallback(async (s: string) => {
    const id = listIdRef.current(s);
    const n = (asked.current.get(s) ?? 0) + 1;
    asked.current.set(s, n);
    const r = await apiOf(s).fetch(`/api/chats?all=${all ? 1 : 0}&archived=${archivedView ? 1 : 0}`).catch(() => null);
    if (asked.current.get(s) !== n) return;
    if (!r?.ok) {
      if (hubMode && (!r || r.status === 502)) patchInfo(s, { state: "off" });
      return;
    }
    const rows: ChatRow[] = await r.json();
    if (asked.current.get(s) !== n) return;
    fetched.current.add(id);
    for (const c of rows) known.current.set(keyOf(s, c.id), { ...c, server: s, key: keyOf(s, c.id), off: false });
    const cur = keyOf(currentRef.current.server, currentRef.current.id);
    // Sounds and desktop popups: a chat that finished or started waiting on you (a sound in its
    // colour's note); the open one only when the window isn't in front.
    if (soundsRef.current || popupsRef.current) {
      const heard = (c: ChatRow) => keyOf(s, c.id) !== cur || !document.hasFocus();
      for (const c of rows) {
        const k = keyOf(s, c.id);
        const kind = c.waiting && !wasWaiting.current.get(k) ? "waiting"
          : wasRunning.current.get(k) && !c.running && !c.waiting ? "done" : null;
        if (!kind || !heard(c)) continue;
        if (soundsRef.current) play(kind, c.color);
        if (popupsRef.current) popup(kind, c, () => openRef.current({ server: s, id: c.id }));
      }
    }
    for (const c of rows) { wasRunning.current.set(keyOf(s, c.id), c.running); wasWaiting.current.set(keyOf(s, c.id), c.waiting); }
    setLists((l) => ({ ...l, [id]: rows }));
    if (hubMode) store.set(`hub.list.${id}`, JSON.stringify(rows));
  }, [all, archivedView, viewSig]);
  const loadRef = useRef(loadChats);
  loadRef.current = loadChats;

  // One merged list, in the order chats were started; a server that is off shows its last list, greyed.
  const [serverFilter, setServerFilterState] = useState<string | null>(() => (hubMode && store.get("hub.filter")) || null);
  const setServerFilter = (s: string | null) => { setServerFilterState(s); store.set("hub.filter", s ?? ""); };
  const filter = serverFilter && servers.some((s) => s.name === serverFilter) && shownServer(serverFilter) ? serverFilter : null;
  const rowsAll = useMemo(
    () => mergeByStart(servers.filter((s) => shownServer(s.name)).map((s) => (lists[listId(s.name)] ?? []).map((c) => ({ ...c, server: s.name, key: keyOf(s.name, c.id), off: hubMode && info[s.name]?.state === "off" })))),
    [lists, info, viewSig, serverNames, all, archivedView],
  );
  const chats = filter ? rowsAll.filter((c) => c.server === filter) : rowsAll;

  // Search: two letters or more search what was said in the chats (each server's index), not only titles.
  const [found, setFound] = useState<(SearchFound & { server: string })[] | null>(null);
  useEffect(() => {
    const text = search.trim();
    if (text.length < 2) { setFound(null); return; }
    const ctl = new AbortController();
    const t = setTimeout(() => {
      const asking = servers.filter((s) => shownServer(s.name) && (!filter || s.name === filter));
      Promise.all(asking.map((s) =>
        apiOf(s.name).fetch(`/api/search?q=${encodeURIComponent(text)}&all=${all ? 1 : 0}&archived=${archivedView ? 1 : 0}`, { signal: ctl.signal })
          .then((r) => (r.ok ? r.json() : null))
          .then((f: SearchFound[] | null) => f && f.map((x) => ({ ...x, server: s.name })))
          .catch(() => null),
      )).then((parts) => {
        if (ctl.signal.aborted || parts.every((p) => p === null)) return;
        setFound(parts.flatMap((p) => p ?? []));
      });
    }, 180);
    return () => { clearTimeout(t); ctl.abort(); };
  }, [search, all, archivedView, viewSig, filter]);
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
  const openHit = (at: At, uuid: string | null) => {
    endRailSearch();
    if (keyOf(at.server, at.id) !== curKey) open(at);
    else if (phone) showChat();
    if (uuid) setJumpTo(uuid);
  };

  // Someone else's chats are read only: no new chat there, so open their latest one (the last) instead.
  const readOnly = hubMode ? person !== null : me?.readOnly ?? false;
  const landed = useRef(false);
  useEffect(() => {
    if (!readOnly || landed.current || fromPath() || !chats.length) return;
    landed.current = true;
    const last = chats.at(-1)!;
    open({ server: last.server, id: last.id }, true, false);
  }, [readOnly, chats]);
  // Each server's list, again when the view or a toggle changes (the hub shows the last list seen meanwhile).
  useEffect(() => {
    if (hubMode)
      setLists((l) => {
        const next = { ...l };
        for (const s of servers) {
          const id = listId(s.name);
          if (!next[id]) next[id] = JSON.parse(store.get(`hub.list.${id}`) || "null") ?? undefined;
        }
        return next;
      });
    for (const s of servers) if (shownServer(s.name)) void loadChats(s.name);
    store.set("all", all ? "1" : "0");
  }, [loadChats, serverNames]);

  // One chat socket per server (in the view: ?view=); a server's events, list changes and meta come on it.
  const conns = useRef(new Map<string, Conn>());
  const [ready, setReady] = useState<Record<string, boolean>>({});
  const offTimers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const sendTo = useCallback((s: string, m: unknown) => conns.current.get(s)?.send(m), []);
  const handlers = useRef<{ msg: (s: string, m: any) => void; open: (s: string) => void; ready: (s: string) => void; down: (s: string) => void }>(null!);
  handlers.current = {
    msg: (s, m) => {
      if (m.t === "chats_changed") return void loadRef.current(s);
      if (m.t === "meta") return void setMetas((x) => ({ ...x, [s]: m.meta }));
      const cur = currentRef.current;
      if (s !== cur.server || m.chat !== cur.id) return;
      if (m.t === "history") {
        const last = m.pictures.at(-1);
        setLive((l) => ({
          ...l, loaded: true, items: m.items, partial: m.partial, running: m.running, since: m.since || Date.now(), scene: m.scene,
          changed: last?.changed ?? [], seq: l.seq + 1, pictures: m.pictures, asks: m.asks, permits: m.permits,
          tasks: m.tasks ?? [], subs: m.subs ?? {}, context: m.context, settings: m.settings, model: m.model, changes: m.changes ?? [],
        }));
      } else if (m.t === "event") setLive((l) => apply(l, m.ev));
    },
    open: (s) => {
      const cur = currentRef.current;
      if (cur.server === s) sendTo(s, { t: "open", chat: cur.id });
    },
    ready: (s) => {
      setReady((r) => ({ ...r, [s]: true }));
      if (!hubMode) return;
      clearTimeout(offTimers.current.get(s));
      offTimers.current.delete(s);
      // Back (or there for the first time): who you are there, and its list.
      void loadMe(s);
      void loadRef.current(s);
    },
    down: (s) => {
      setReady((r) => ({ ...r, [s]: false }));
      if (!hubMode) return;
      // A restart comes back within a moment; only a server still away 3 s after it went is "off"
      // (counted from the first failure: each failed retry closes again).
      if (offTimers.current.has(s)) return;
      offTimers.current.set(s, setTimeout(() => {
        offTimers.current.delete(s);
        if (!conns.current.get(s)?.ready) setInfo((m) => (m[s] && m[s].state !== "noProfile" ? { ...m, [s]: { ...m[s], state: "off" } } : m));
      }, 3000));
    },
  };
  const wanted = servers.filter((s) => shownServer(s.name)).map((s) => `${s.name}\t${apiOf(s.name).ws()}`).join("\n");
  useEffect(() => {
    const want = new Map(wanted ? wanted.split("\n").map((l) => l.split("\t") as [string, string]) : []);
    for (const [s, c] of conns.current)
      if (want.get(s) !== c.url) {
        c.close();
        conns.current.delete(s);
        setReady((r) => ({ ...r, [s]: false }));
      }
    for (const [s, url] of want)
      if (!conns.current.has(s))
        conns.current.set(s, new Conn(url, {
          msg: (m) => handlers.current.msg(s, m),
          open: () => handlers.current.open(s),
          ready: () => handlers.current.ready(s),
          down: () => handlers.current.down(s),
        }));
  }, [wanted]);
  useEffect(() => () => {
    for (const c of conns.current.values()) c.close();
    conns.current.clear();
    for (const t of offTimers.current.values()) clearTimeout(t);
  }, []);
  const up = ready[current.server] ?? false;
  // Messages about the open chat go to its server.
  const send = useCallback((m: unknown) => sendTo(currentRef.current.server, m), [sendTo]);

  // show: on a phone, go to the chat's screen (opening a chat in the background, e.g. another
  // person's latest, leaves the list showing).
  const open = useCallback((at: At, push = true, show = push) => {
    setCurrent(at);
    setLive(fresh(at.id));
    setViewing(null);
    setEditing(false);
    setDrawer(false);
    if (show && phoneRef.current) {
      if (screenRef.current === "list") {
        history.replaceState({ screen: "list" }, "");
        if (push) history.pushState({ screen: "chat", list: true }, "", pathOf(at));
      } else if (push) history.replaceState(history.state, "", pathOf(at)); // chat to chat: the list stays right behind
      setScreen("chat");
    } else if (push) history.pushState(null, "", pathOf(at));
    sendTo(at.server, { t: "open", chat: at.id });
  }, [sendTo]);
  openRef.current = open;
  // Phone: the open chat's screen, from the list.
  const showChat = () => {
    history.replaceState({ screen: "list" }, "");
    history.pushState({ screen: "chat", list: true }, "");
    setScreen("chat");
  };
  // Phone: back to the list (the picture drawer closes first).
  const goList = () => {
    closePicture();
    if (history.state?.list) return history.back();
    history.replaceState({ screen: "list" }, "");
    setScreen("list");
  };

  const newChat = useCallback(() => open({ server: defaultServer, id: crypto.randomUUID() }), [open, defaultServer]);
  // A chat picked from the list nods "yes" and opens; the open one shakes "no, already here" and
  // isn't loaded again. (Web Animations, so React re-rendering the class doesn't cut them short.)
  const pick = (c: Row, e: React.MouseEvent<HTMLElement>) => {
    const el = e.currentTarget;
    if (c.key === curKey && phone) return showChat();
    if (c.key === curKey) {
      el.animate([{ translate: "0 0" }, { translate: "-4px 0" }, { translate: "4px 0" }, { translate: "-3px 0" }, { translate: "2px 0" }, { translate: "0 0" }], { duration: 360, easing: "ease-in-out" });
      return;
    }
    el.animate([{ translate: "0 0" }, { translate: "0 4px" }, { translate: "0 -3px" }, { translate: "0 3px" }, { translate: "0 0" }], { duration: 420, easing: "ease-in-out" });
    open({ server: c.server, id: c.id });
  };

  // An address's chat: /c/<server>/<chat>, or an old /c/<chat> on the server whose list has it.
  const serverOf = (id: string) => servers.find((s) => Object.entries(lists).some(([k, rows]) => k.startsWith(`${s.name}|`) && rows?.some((c) => c.id === id)))?.name;
  useEffect(() => {
    const pop = (e: PopStateEvent) => {
      if (phoneRef.current && e.state?.screen === "list") { closePicture(); setScreen("list"); return; }
      setScreen("chat");
      const p = fromPath();
      if (!p) return open({ server: defaultServer, id: crypto.randomUUID() }, false);
      open({ server: !hubMode ? "" : p.server ?? serverOf(p.id) ?? defaultServer, id: p.id }, false);
    };
    window.addEventListener("popstate", pop);
    return () => window.removeEventListener("popstate", pop);
  }, [open, lists, defaultServer, closePicture]);
  // An old link: once a server's list has the chat, open it there and give the address its server;
  // when every server answered without it, it opens on the default server.
  useEffect(() => {
    if (!resolving) return;
    const found = serverOf(current.id);
    const settled = servers.every((s) => !shownServer(s.name) || info[s.name]?.state === "off" || fetched.current.has(listId(s.name)));
    if (!found && !(settled && defaultServer)) return;
    setResolving(false);
    const at = { server: found ?? defaultServer, id: current.id };
    history.replaceState(null, "", pathOf(at));
    open(at, false);
  }, [resolving, lists, info, defaultServer]);
  // A new, unsent chat on a server that can't be used (no profile there, or nothing yet): the default one.
  const started = !!(rowsAll.some((c) => c.key === curKey) || known.current.get(curKey) || live.items.length);
  useEffect(() => {
    if (!hubMode || resolving || started || !defaultServer || current.server === defaultServer) return;
    if (current.server && usable.includes(current.server)) return;
    open({ server: defaultServer, id: current.id }, false);
  }, [resolving, started, defaultServer, current.server, usable.join(",")]);

  const row = chats.find((c) => c.key === curKey) ?? known.current.get(curKey);
  // Done since you last looked (decision 24): the server keeps when each chat was last open, so the
  // rail's mark and the phone's badge agree on every device. The open chat, while it shows, is
  // seen there; never from another profile's read-only view.
  const [visible, setVisible] = useState(() => document.visibilityState === "visible");
  useEffect(() => {
    const on = () => setVisible(document.visibilityState === "visible");
    document.addEventListener("visibilitychange", on);
    return () => document.removeEventListener("visibilitychange", on);
  }, []);
  const doneMark = (c: Row) => !readOnly && !!c.unseen && c.key !== curKey;
  useEffect(() => {
    if (row?.unseen && visible && !readOnly && (!phone || screen === "chat")) void openApi.fetch(`/api/chats/${current.id}/seen`, { method: "POST" }).catch(() => {});
  }, [row?.unseen, curKey, visible, readOnly, phone, screen]);
  const title = row?.title ?? (live.items.length ? "New chat" : "New chat");
  useEffect(() => { document.title = `${title} · ${brandName(hubMode ? null : me?.name)}`; }, [title, me]);

  const onSend = (text: string, images: SentImage[]) => {
    setViewing(null);
    // A typed message while Claude is asking answers the question.
    if (live.asks.length && text && !images.length) {
      const ask = live.asks[0];
      send({ t: "answer", chat: current.id, id: ask.id, answers: Object.fromEntries(ask.questions.map((q) => [q.question, text])) });
      return;
    }
    if (!fromPath()) history.replaceState(history.state, "", pathOf(current));
    if (hubMode) store.set("hub.server", current.server);
    send({ t: "send", chat: current.id, text, images });
  };
  const onStop = () => send({ t: "stop", chat: current.id });

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
    await openApi.fetch(`/api/chats/${current.id}`, { method: "PATCH", body: JSON.stringify({ title: t.trim() }) });
  };
  // Archive hides the chat (the open one moves on to the next); in the archived view it brings it back.
  const archiveChat = async (at: At, archived: boolean) => {
    await apiOf(at.server).fetch(`/api/chats/${at.id}`, { method: "PATCH", body: JSON.stringify({ archived }) });
    if (!archived || keyOf(at.server, at.id) !== curKey) return;
    const next = chats.find((c) => c.key !== curKey);
    if (next) open({ server: next.server, id: next.id });
    else newChat();
  };
  const setArchived = (archived: boolean) => archiveChat(current, archived);
  // Fork: a new chat holding the chat up to the end of a turn (at: a user message; none = its last
  // finished turn), on the same server. Opens the fork; returns the error if there is one.
  const forkChat = async (chat: At, at?: string): Promise<string | null> => {
    const r = await apiOf(chat.server).fetch(`/api/chats/${chat.id}/fork`, { method: "POST", body: JSON.stringify(at ? { at } : {}) }).catch((err) => err as Error);
    const j = r instanceof Error ? { error: r.message } : await r.json().catch(() => ({ error: r.statusText }));
    if (j.error || !j.id) return String(j.error ?? "No fork made.");
    open({ server: chat.server, id: j.id });
    return null;
  };
  // The right-click menu on a chat (rail circle or list row).
  const [menu, setMenu] = useState<{ chat: MenuChat; server: string; x: number; y: number } | null>(null);
  const closeMenu = useCallback(() => setMenu(null), []);
  const menuFor = (c: Row) => (e: React.MouseEvent) => {
    if (readOnly) return;
    e.preventDefault();
    setMenu({ chat: { id: c.id, title: c.title, color: c.color, archived: archivedView }, server: c.server, x: e.clientX, y: e.clientY });
  };

  // The right-click menu on the context meter (ctx ring or the sidebar's Context bar): compact.
  const [ctxMenu, setCtxMenu] = useState<{ x: number; y: number } | null>(null);
  const closeCtxMenu = useCallback(() => setCtxMenu(null), []);
  const onCtxMenu = (e: React.MouseEvent) => { e.preventDefault(); if (!readOnly) setCtxMenu({ x: e.clientX, y: e.clientY }); };
  const compact = readOnly || live.running ? undefined : () => onSend("/compact", []);

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
    for (const el of document.querySelectorAll<HTMLElement>(".sidebar nav .chat-row.active, .rail-dots .rail-chat.on")) {
      const list = el.parentElement!;
      const r = el.getBoundingClientRect(), l = list.getBoundingClientRect();
      if (r.bottom > l.bottom) list.scrollTop += r.bottom - l.bottom + 8;
      else if (r.top < l.top) list.scrollTop -= l.top - r.top + 8;
    }
  }, [curKey, chats.length, live.items.length > 0]);

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
      chat={current.id}
      tasks={live.tasks}
      subs={live.subs}
      changes={shownChanges}
      changesLabel={changesLabel}
      folder={live.settings.folder}
      onStop={(task) => send({ t: "stop_task", chat: current.id, task })}
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
    const move = (m: PointerEvent) => setChatW(Math.round(Math.min(main.width - 6 - 280, Math.max(300, m.clientX - main.left))));
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      setChatW((w) => { store.set("chatW", String(w)); return w; });
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
  const fold = useFoldMotion(folded && !phone, chatW, phone);
  const picUnseen = phone && !picOpen && (live.pictures.at(-1)?.at ?? 0) > picAt;
  // Phone: the other chats waiting on you or done since you looked, as a count on the Chats button.
  const others = chats.filter((c) => c.key !== curKey && !c.off);
  const waitingElsewhere = others.filter((c) => c.waiting).length;
  const doneElsewhere = others.filter((c) => !c.waiting && doneMark(c)).length;
  const leftBox = useRef<HTMLElement>(null);
  const rightBox = useRef<HTMLElement>(null);
  useSwipe(leftBox, phone && screen === "chat" && !picOpen, { right: goList, left: () => setPicOpen(true) });
  useSwipe(rightBox, phone && picOpen, { right: closePicture }, true);
  const pictureBehind = rightView === "timeline" && (live.pictures.at(-1)?.at ?? 0) > timelineAt;

  const q = search.trim().toLowerCase();
  const list = q ? chats.filter((c) => c.title.toLowerCase().includes(q)) : chats;
  const tags = useMemo(() => projectTags(chats.map((c) => c.project)), [chats]);
  // The rail: passive chats last, in their own order.
  const railList = [...list.filter((c) => c.color !== PASSIVE), ...list.filter((c) => c.color === PASSIVE)];
  const railBox = useRef<HTMLDivElement>(null);
  useSlide(railBox, `${showSidebar ? "" : "rail:"}${railList.map((c) => c.key).join(",")}`);

  // Whose chats in the hub: the profile names over every server; yours are the ones you are.
  const people = hubMode ? [...new Set(servers.flatMap((s) => (info[s.name]?.state !== "noProfile" ? info[s.name]?.me?.profiles ?? [] : [])))] : [];
  const own = new Set(servers.map((s) => (info[s.name]?.state !== "noProfile" ? info[s.name]?.me?.profile : null)).filter((p): p is string => !!p));
  const pickPerson = (p: string | null) => {
    setPerson(p);
    store.set("hub.view", p ?? "");
    landed.current = false;
    // As the page did on a new view: start over at /, and another person's latest chat opens once their list is in.
    history.pushState(null, "", "/");
    open({ server: defaultServer, id: crypto.randomUUID() }, false);
  };
  // Settings are each server's: the open chat's by default.
  const upServers = views.filter((v) => v.state === "up").map((v) => v.name);
  const openSettings = () => {
    setSettingsServer(!hubMode ? "" : upServers.includes(current.server) ? current.server : upServers[0] ?? current.server);
    setSettingsOpen(true);
  };
  const settingsMe = info[settingsServer]?.me ?? null;
  // Whose chats are read (only).
  const whose = hubMode ? person : me?.viewing ?? null;
  const offNote = hubMode && curInfo?.state === "off" ? `${labelOf(current.server)} is off` : null;
  const markOf = (c: Row) => (showMarks ? <ServerMark mark={marks.get(c.server) ?? ""} label={labelOf(c.server)} off={c.off} /> : null);

  if (!hubMode && me && !me.profile) return <NoProfile me={me} />;
  if (hub && (!servers.length || views.every((v) => v.state === "noProfile")))
    return (
      <>
        <HubEmpty hub={hub} servers={views} onAdmin={setAdminTab} />
        {adminTab && <AdminDialog tab={adminTab} close={closeAdmin} />}
      </>
    );

  return (
    <ApiCtx.Provider value={openApi}>
    <div className={`app ${showSidebar ? "" : "no-sidebar"} ${narrow ? "narrow" : ""} ${readOnly ? "read-only" : ""}`}>
      <TipBubble />
      {me?.profile && !readOnly && !settingsOpen && (!hubMode || curInfo?.state === "up") && <GithubPrompt key={current.server} profile={me.profile} />}
      {settingsOpen && settingsMe && (
        <SettingsDialog
          api={apiFor(settingsServer)}
          me={settingsMe}
          onMe={(m) => patchInfo(settingsServer, { me: m })}
          close={closeSettings}
          top={hubMode ? <SettingsServers servers={views.map((v) => ({ ...v, me: info[v.name]?.me ?? null }))} pick={settingsServer} onPick={setSettingsServer} /> : undefined}
        />
      )}
      {adminTab && <AdminDialog tab={adminTab} close={closeAdmin} />}
      {formsOpen && <FormsGallery close={closeForms} />}
      {menu && (
        <ChatMenu
          {...menu}
          close={closeMenu}
          onColor={(color) => sendTo(menu.server, { t: "settings", chat: menu.chat.id, color })}
          onWake={() => sendTo(menu.server, { t: "wake", chat: menu.chat.id })}
          onRename={(title) => void apiOf(menu.server).fetch(`/api/chats/${menu.chat.id}`, { method: "PATCH", body: JSON.stringify({ title }) })}
          onFork={() => forkChat({ server: menu.server, id: menu.chat.id })}
          onArchive={(archived) => void archiveChat({ server: menu.server, id: menu.chat.id }, archived)}
        />
      )}
      {ctxMenu && live.context && (
        <ContextMenu {...ctxMenu} close={closeCtxMenu} title={`Context ${live.context.percent}% · ${Math.round(live.context.used / 1000)}k of ${Math.round(live.context.max / 1000)}k`} onCompact={compact} />
      )}
      {showSidebar && !phone && <div className="backdrop" onClick={() => { setDrawer(false); endRailSearch(); }} />}
      {!readOnly && (live.asks.length > 0 || live.permits.length > 0) && (
        <>
          <div className="ask-modal" hidden={peek} role="dialog" aria-modal="true" aria-label="Claude asks">
            {live.permits.map((p) => <PermitPanel key={p.id} permit={p} onPermit={(allow) => send({ t: "permit", chat: current.id, id: p.id, allow })} />)}
            {live.asks.map((a) => <AskPanel key={a.id} ask={a} active={!peek} onPeek={peekAsk} onAnswer={(answers) => send({ t: "answer", chat: current.id, id: a.id, answers })} />)}
          </div>
          {peek && <button className="ask-return" onClick={() => setPeek(false)}>Claude asks · answer</button>}
        </>
      )}
      <button className="icon menu-toggle" onClick={toggleSidebar} title={showSidebar ? "Hide chats (Ctrl+B)" : "Show chats (Ctrl+B)"} aria-expanded={showSidebar}>
        <svg className="burger" viewBox="0 0 18 18" aria-hidden="true"><path d="M3 4.6h12M3.4 9h11.3M3 13.4h12" /></svg>
      </button>
      <div className="sidebar-slot">
        {!showSidebar && !phone && (
          <nav className="rail" aria-label="Chats">
            <div className="rail-dots" ref={railBox}>
              {!readOnly && <button className="rail-dot add" onClick={newChat} title="New chat (Alt+N)">+</button>}
              <button className="rail-dot add rail-search" onClick={searchFromRail} {...tipProps("Search chats")} aria-label="Search chats">
                <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5" /><path d="M12.3 12.3L17 17" /></svg>
              </button>
              {railList.map((c, i) => (
                <button
                  key={c.key}
                  data-slide={c.key}
                  className={`rail-chat ${c.key === curKey ? "on" : ""} ${c.off ? "off" : ""} ${c.color === PASSIVE ? `passive ${railList[i - 1]?.color !== PASSIVE ? "first" : ""} ${i === railList.length - 1 ? "last" : ""}` : ""}`}
                  style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}
                  onClick={(e) => pick(c, e)}
                  onContextMenu={menuFor(c)}
                  {...tipProps(`${c.project ? `${c.title} · ${c.project}` : c.title}${hubMode ? ` · ${labelOf(c.server)}${c.off ? " is off" : ""}` : ""}`, chatColorVar(c.color))}
                  aria-label={c.title}
                >
                  <span className="rail-dot">
                    <DotFace project={c.project} tag={c.project ? tags.get(c.project) ?? "" : ""} logo={c.logo} api={apiOf(c.server)} />
                    <StateBadge state={c.waiting ? "waiting" : c.running ? "thinking" : doneMark(c) ? "done" : null} />
                    <AgentMoons n={c.agents} />
                  </span>
                  <span className="rail-name">{c.title}</span>
                  {hubMode && <span className="rail-server">{labelOf(c.server)}</span>}
                </button>
              ))}
            </div>
            <button className={`rail-forms rail-sound ${sounds ? "on" : ""}`} onClick={() => toggleSounds(!sounds)} {...tipProps(sounds ? "Sounds on: click to mute" : "Sounds off: click to hear when a chat finishes or waits")} aria-label="Sounds" aria-pressed={sounds}>
              <SpeakerIcon on={sounds} />
            </button>
            <button className="rail-forms" onClick={() => setFormsOpen(true)} {...tipProps("Forms: everything Claude can draw, and your templates")} aria-label="Forms">
              <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="3" width="6" height="6" rx="1.5" /><circle cx="14" cy="6" r="3" /><path d="M3 15h6M11 12.5l3 3 3-3" /></svg>
            </button>
            <RailMeters meta={meta} context={live.context} onCompact={compact} onContextMenu={onCtxMenu} />
          </nav>
        )}
        <aside className="sidebar" aria-hidden={!showSidebar} inert={!showSidebar}>
          {hub ? <Brand me={null} hub={hub} token={meta.accountLabel ?? me?.claudeToken?.name ?? null} /> : <Brand me={me} token={meta.accountLabel ?? me?.claudeToken?.name ?? null} />}
          {hubMode ? <PeopleTabs names={people} own={own} viewing={person} onPick={pickPerson} /> : me && <ProfileTabs me={me} />}
          {hubMode && (servers.length > 1 || views.some((v) => v.state !== "up" && v.state !== "loading") || views.some((v) => v.update)) && (
            <ServerFilter servers={views} marks={marks} pick={filter} onPick={setServerFilter} />
          )}
          {!readOnly && <button className="new" onClick={newChat} title="New chat (Alt+N)">New chat</button>}
          <label className="search-box"><input ref={searchBox} className="search" placeholder="Search chats" value={search} onChange={(e) => setSearch(e.target.value)} onKeyDown={(e) => { if (e.key === "Escape" && railSearch) { e.stopPropagation(); endRailSearch(); } }} /></label>
          <nav>
            {hubMode && views.filter((v) => v.state === "off" && shownServer(v.name) && (!filter || filter === v.name)).map((v) => (
              <p key={v.name} className="server-off-note">{v.label} is off · its chats as last seen</p>
            ))}
            {found && (
              <>
                {found.map((f) => {
                  const c = known.current.get(keyOf(f.server, f.id));
                  if (!c) return null;
                  return (
                    <div key={c.key} className="found" style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}>
                      <button className={`chat-row ${c.key === curKey ? "active" : ""} ${c.color ? "colored" : ""}`} onClick={() => openHit({ server: c.server, id: c.id }, null)} onContextMenu={menuFor(c)}>
                        <span className="t">{f.title ? <Marked text={c.title} words={search} /> : c.title}</span>
                        {markOf(c)}
                        <span className="when">{f.count > 0 ? `${f.count} hit${f.count === 1 ? "" : "s"}` : ago(c.at)}</span>
                      </button>
                      {f.hits.map((h) => (
                        <button key={h.uuid} className={`found-hit ${h.role}`} onClick={() => openHit({ server: c.server, id: c.id }, h.uuid)}>
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
                key={c.key}
                className={`chat-row ${c.key === curKey ? "active" : ""} ${c.color ? "colored" : ""} ${c.off ? "off" : ""}`}
                style={{ "--chat": chatColorVar(c.color) } as React.CSSProperties}
                onClick={(e) => { endRailSearch(); pick(c, e); }}
                onContextMenu={menuFor(c)}
                {...tipProps(c.off ? `${c.title} · ${labelOf(c.server)} is off` : c.title, chatColorVar(c.color), !c.off)}
              >
                {c.running && !c.off && <span className="run-dot" title="Working" />}
                <span className="t">{c.title}</span>
                {phone && !c.off && (c.waiting ? <span className="row-mark waiting" title="Waiting for you">?</span> : doneMark(c) ? <span className="row-mark done" title="Done">✓</span> : null)}
                {markOf(c)}
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
            onContextMenu={onCtxMenu}
            saved={me?.claudeTokens.map((t) => t.name) ?? []}
            onSwitch={async (name) => {
              const s = current.server;
              const next = await useClaudeToken(apiFor(s), name);
              if (next) setInfo((m) => (m[s]?.me ? { ...m, [s]: { ...m[s], me: { ...m[s].me!, ...next } } } : m));
            }}
          />
          <div className="side-foot">
            <button className={`archived-toggle ${archivedView ? "on" : ""}`} onClick={() => setArchivedView((v) => !v)}>
              <ArchiveIcon /> {archivedView ? "Back to chats" : "Archived"}
            </button>
            <label className="toggle">
              <input
                type="checkbox"
                checked={sounds}
                onChange={(e) => toggleSounds(e.target.checked)}
              />
              Sounds
            </label>
            <label className="toggle">
              <input
                type="checkbox"
                checked={popups}
                onChange={(e) => void togglePopups(e.target.checked)}
              />
              Desktop popups
            </label>
            <label className="toggle">
              <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} />
              Show all ~/Work sessions
            </label>
            {!readOnly && <button className="archived-toggle" onClick={openSettings} title={me?.email ? `Signed in as ${me.email}` : undefined}>
              ⚙ Settings
            </button>}
            {hub?.admin && (
              <span className="admin-links">
                <button className="archived-toggle" onClick={() => setAdminTab("servers")} title="The servers this hub reaches (admins)">Servers</button>
                <button className="archived-toggle" onClick={() => setAdminTab("access")} title="Who may use which server (admins)">Access</button>
              </span>
            )}
          </div>
        </aside>
      </div>
      <main
        ref={fold.ref}
        className={`split ${fold.shown ? "folded" : ""} ${fold.cls}`}
        style={{
          gridTemplateColumns: fold.cols ?? (fold.shown ? `minmax(300px, 1fr) 6px ${EDGE}px` : `max(300px, min(${chatW}px, calc(100% - 286px))) 6px minmax(280px, 1fr)`),
          ...(fold.width !== null ? { "--fold-w": `${fold.width}px` } : {}),
        } as React.CSSProperties}
      >
        <section className="left" ref={leftBox}>
          <header className={`chat-head ${live.settings.color ? "colored" : ""}`}>
            {phone && (
              <button className="back-to-list" onClick={goList} aria-label="Chats">
                <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M12.5 4L6.5 10l6 6" /></svg>
                Chats
                {waitingElsewhere + doneElsewhere > 0 && <span className={`count ${waitingElsewhere ? "waiting" : ""}`}>{waitingElsewhere + doneElsewhere}</span>}
              </button>
            )}
            {!readOnly && <ColorPicker color={live.settings.color} onPick={(color) => send({ t: "settings", chat: current.id, color })} />}
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
            {showMarks && current.server && (row || live.items.length > 0) && <span className="folder-chip server-chip" title={`On ${labelOf(current.server)}`}>{labelOf(current.server)}</span>}
            {live.settings.folder && <span className="folder-chip" title={`Working folder: ${live.settings.folder}`}>{clip(tilde(live.settings.folder), 28)}</span>}
            {activity}
            {offNote ? <span className="offline">{offNote}</span> : !up && <span className="offline">reconnecting…</span>}
            {phone && (
              <button className={`icon pic-open ${picUnseen ? "unseen" : ""}`} onClick={() => setPicOpen(true)} aria-label={picUnseen ? "New picture · show" : "Show picture"}>
                <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="3.5" width="14" height="13" rx="2.5" /><circle cx="8" cy="9" r="2" /><path d="M11 13.5l2-2 2.5 2.5" /></svg>
              </button>
            )}
          </header>
          <Messages
            chat={current.id}
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
            onFork={readOnly ? undefined : (at) => void forkChat(current, at).then((error) => error && setLive((l) => ({ ...l, error })))}
          />
          {readOnly ? (
            <div className="read-only-note">{whose ? `${whose[0].toUpperCase()}${whose.slice(1)}'s chat` : "Someone else's chat"} · read only</div>
          ) : <>
          {showMarks && !started && !resolving && (
            <NewChatServer
              servers={views}
              marks={marks}
              pick={current.server}
              onPick={(s) => {
                if (s === current.server) return;
                store.set("hub.server", s);
                const at = { server: s, id: crypto.randomUUID() };
                if (location.pathname !== "/") history.replaceState(null, "", pathOf(at));
                open(at, false);
              }}
            />
          )}
          <Composer
            running={live.running}
            asking={live.asks.length > 0}
            onSend={onSend}
            onStop={onStop}
            focusKey={curKey}
            initialText={startText}
            commands={meta.commands}
            actions={<>
              {row && !readOnly && (
                row.archived
                  ? <button className="icon archive" onClick={() => setArchived(false)} title="Unarchive"><ArchiveIcon out /></button>
                  : <button className="icon archive" onClick={() => setArchived(true)} title="Archive"><ArchiveIcon /></button>
              )}
            </>}
            tools={<Pickers meta={meta} settings={live.settings} model={live.model} onSettings={(next) => send({ t: "settings", chat: current.id, ...next })} />}
          />
          </>}
        </section>
        <div className="divider" onPointerDown={fold.shown ? undefined : dragSplit} />
        {phone && picOpen && <div className="pic-backdrop" onClick={closePicture} />}
        {fold.shown ? (
          <section className="right fold-edge">
            <button className={`unfold ${unseen ? "unseen" : ""}`} onClick={toggleFold} title={unseen ? "New picture · show (Alt+P)" : "Show picture (Alt+P)"} aria-label="Show picture">
              <span className="edge-line" />
            </button>
          </section>
        ) : (
        <section
          ref={rightBox}
          className={`right ${phone ? "pic-drawer" : ""} ${phone && picOpen ? "open" : ""}`}
          style={fold.rightWidth ? { width: fold.rightWidth } : undefined}
          onMouseMove={(e) => setNearBar(e.clientY > e.currentTarget.getBoundingClientRect().bottom - 96)}
          onMouseLeave={() => setNearBar(false)}
        >
          {rightView === "timeline" ? (
          <div className="right-tl">
            <TimelineView chat={current.id} live={live.running || live.tasks.some((t) => t.status === "running")} />
          </div>
          ) : (
          <div className="right-scroll">
          {!readOnly && phone && (
            <FormRow
              forms={live.settings.forms ?? {}}
              hidden={hiddenForms}
              onChange={(forms) => send({ t: "settings", chat: current.id, forms })}
              cols={live.settings.cols ?? 0}
              onCols={(cols) => send({ t: "settings", chat: current.id, cols })}
            />
          )}
          <div className={`stage ${viewing ? "past" : ""}`}>
            <PictureSend.Provider value={readOnly ? null : send}>
              <PictureView scene={shown.scene} changed={shown.changed} seq={shown.seq} chat={viewing || readOnly ? undefined : current.id} browse={current.id} cols={live.settings.cols ?? 0} />
            </PictureSend.Provider>
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
            onFold={phone ? closePicture : toggleFold}
            foldDisabled={live.asks.length > 0}
          />
          </div>
        </section>
        )}
      </main>
      {/* The right rail, mirroring the chats rail: this chat's forms. On phones they are a row in the picture drawer. */}
      {!phone && (
        <nav className="rail right-rail" aria-label="Forms">
          {!readOnly && (
            <FormRow
              rail
              forms={live.settings.forms ?? {}}
              hidden={hiddenForms}
              onChange={(forms) => send({ t: "settings", chat: current.id, forms })}
              cols={live.settings.cols ?? 0}
              onCols={(cols) => send({ t: "settings", chat: current.id, cols })}
            />
          )}
        </nav>
      )}
    </div>
    </ApiCtx.Provider>
  );
}

// The chalk masks are drawn before the first paint, so nothing shows without its chalk.
bakeChalk();
createRoot(document.getElementById("root")!).render(<Root />);

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
// A chalk speaker; waves when sounds are on, a cross when off.
function SpeakerIcon({ on }: { on: boolean }) {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true">
      <path d="M3 8h3l4-3.5v11L6 12H3z" />
      {on ? <path d="M13 7.5c1 1.4 1 3.6 0 5M15.4 5.5c2 2.5 2 6.5 0 9" /> : <path d="M13.5 8l4 4M17.5 8l-4 4" />}
    </svg>
  );
}

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
// A rail circle's face: the project's logo big with the chat's letter in a small circle; without a
// logo, the letter big and the project's short name as a tinted tag.
// Inside a rail circle: the project's logo, else its short name; a chat without a project, a dot.
// (The chat's title and, on the hub, its server's name sit under the circle.)
// api: the chat's server.
function DotFace({ project, tag, logo, api }: { project: string | null; tag: string; logo: boolean; api: Api }) {
  const [broken, setBroken] = useState(false);
  if (project && logo && !broken)
    return <img className="rail-logo" src={api.src(`/api/projects/${encodeURIComponent(project)}/logo`)} alt="" onError={() => setBroken(true)} />;
  if (!project) return <span className="rail-blank" aria-hidden="true" />;
  return <span className="rail-tag">{tag}</span>;
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

// Phone swipes, sideways only: on the chat, right goes back to the list and left opens the picture;
// on the picture drawer, right closes it (the drawer follows the finger). Left alone: a touch that
// starts at the screen's edge (Safari's own back and forward), in a field, or in something that
// scrolls sideways (code, tables), and a move that is mostly up or down.
function useSwipe(box: React.RefObject<HTMLElement | null>, enabled: boolean, on: { right?: () => void; left?: () => void }, follow = false) {
  const cb = useRef(on);
  cb.current = on;
  useEffect(() => {
    const el = box.current;
    if (!el || !enabled) return;
    let x0 = 0, y0 = 0, dx = 0, axis: "x" | "y" | null = null, skip = true;
    const reset = () => { el.style.transition = ""; el.style.transform = ""; };
    const start = (e: TouchEvent) => {
      const t = e.touches[0];
      const target = e.target instanceof Element ? e.target : null;
      skip = e.touches.length > 1 || !t || t.clientX < 24 || t.clientX > innerWidth - 24
        || !!target?.closest("input, textarea, select, [contenteditable]") || scrollsSideways(target, el);
      x0 = t?.clientX ?? 0;
      y0 = t?.clientY ?? 0;
      dx = 0;
      axis = null;
    };
    const move = (e: TouchEvent) => {
      const t = e.touches[0];
      if (skip || !t) return;
      dx = t.clientX - x0;
      const dy = t.clientY - y0;
      if (!axis && Math.hypot(dx, dy) > 10) axis = Math.abs(dx) > Math.abs(dy) * 1.5 ? "x" : "y";
      if (follow && axis === "x") {
        el.style.transition = "none";
        el.style.transform = `translateX(${Math.max(0, dx)}px)`;
      }
    };
    const end = () => {
      if (follow) reset();
      if (skip || axis !== "x") return;
      if (dx > 70) cb.current.right?.();
      else if (dx < -70) cb.current.left?.();
    };
    const cancel = () => { if (follow) reset(); skip = true; };
    el.addEventListener("touchstart", start, { passive: true });
    el.addEventListener("touchmove", move, { passive: true });
    el.addEventListener("touchend", end);
    el.addEventListener("touchcancel", cancel);
    return () => {
      el.removeEventListener("touchstart", start);
      el.removeEventListener("touchmove", move);
      el.removeEventListener("touchend", end);
      el.removeEventListener("touchcancel", cancel);
      reset();
    };
  }, [enabled, follow]);
}

// Whether something from `el` up to `root` scrolls sideways (has more width than it shows).
function scrollsSideways(el: Element | null, root: Element): boolean {
  for (; el && el !== root; el = el.parentElement) {
    const x = getComputedStyle(el).overflowX;
    if ((x === "auto" || x === "scroll") && el.scrollWidth > el.clientWidth) return true;
  }
  return false;
}

// Folding the right half slides it shut instead of jumping: its column width moves in px from where
// it is to where it'll be (through --fold-w, a registered length, so it transitions), the pane keeps
// its width meanwhile (so the picture doesn't re-lay out every frame) and fades; only then the edge
// replaces it (or, unfolding, the edge goes first).
const FOLD_MS = 320;
// The folded right half: a thin chalk edge (px).
const EDGE = 16;
function useFoldMotion(folded: boolean, chatW: number, narrow: boolean) {
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
      : total - 6 - Math.min(total - 6 - 280, Math.max(300, chatW));
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

function ArchiveIcon({ out }: { out?: boolean }) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="1.5" y="2.5" width="13" height="3.5" rx="0.8" />
      <path d="M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6" />
      {out ? <path d="M8 12V8.2M6.3 9.8 8 8.1l1.7 1.7" /> : <path d="M6.3 9h3.4" />}
    </svg>
  );
}
