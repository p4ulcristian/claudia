"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  ActiveMap,
  ChatStreamMessage,
  ClaudeEvent,
  FolderPath,
  FolderMetaMap,
  LiveSession,
  SessionSummary,
  TitleMap,
} from "@/lib/types";
import { FOLDER_COLORS } from "@/lib/types";
import type { UsageData } from "@/lib/usage";
import { contextOf, COMPACT_SUGGEST_PCT, COMPACT_AUTO_PCT } from "@/lib/context";
import {
  addFolder as apiAddFolder,
  deleteSession as apiDeleteSession,
  getSessionMeta,
  getFolders,
  getFolderMeta,
  setFolderColor as apiSetFolderColor,
  getLive,
  getSessions,
  getUsage,
  loadSessionDelta,
  readSessionListCache,
  removeFolder as apiRemoveFolder,
  setSessionActive,
  setSessionTitle,
  writeSessionListCache,
} from "./api";
import {
  deleteCachedTranscript,
  getCachedTranscript,
  putCachedTranscript,
  warmTranscriptCache,
} from "./transcriptCache";
import { startChat, stopChat, subscribeChat } from "./stream-chat";
import { foldEvents, type DisplayItem } from "./fold";
import FolderPicker from "./FolderPicker";
import GitPanel from "./GitPanel";
import StreamRenderer from "./StreamRenderer";
import TaskChip from "./TaskChip";
import UsagePanel from "./UsagePanel";
import {
  FontAwesomeIcon,
  faArrowLeft,
  faChartColumn,
  faCircleStop,
  faCompress,
  faFolder,
  faFolderPlus,
  faPlus,
  faXmark,
  faCodeBranch,
  faCircle,
  faCircleCheck,
  faClock,
  faSpinner,
  faPencil,
  faMicrochip,
  faGaugeHigh,
  faCheck,
  faVolumeHigh,
  faVolumeXmark,
} from "./icons";

type View = "folders" | "sessions" | "chat";

const MODELS = [
  { id: "claude-opus-4-8", label: "Opus 4.8" },
  { id: "claude-sonnet-4-6", label: "Sonnet 4.6" },
  { id: "claude-haiku-4-5-20251001", label: "Haiku 4.5" },
  { id: "claude-fable-5", label: "Fable 5" },
];
const DEFAULT_MODEL = "claude-opus-4-8";

function shortName(p: string): string {
  const clean = p.replace(/\/+$/, "");
  if (!clean) return "/";
  return clean.split("/").pop() || clean;
}

// The in-app URL for a folder (sessions view) or a folder+session (chat). Rows
// render this as a real href so Ctrl/Cmd/middle-click open it in a new tab.
function hrefFor(folder: string, sessionId?: string | null): string {
  const sp = new URLSearchParams();
  sp.set("folder", folder);
  if (sessionId !== undefined) sp.set("session", sessionId ?? "new");
  return `/?${sp.toString()}`;
}

// True when the browser should handle a click itself (open in a new tab/window)
// rather than us intercepting it for in-app navigation.
function isModifiedClick(e: {
  metaKey: boolean;
  ctrlKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
  button: number;
}): boolean {
  return e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button === 1;
}

// First human message in a transcript, for the tab title. Mirrors the
// server-side firstUserText used for session summaries.
function titleFromEvents(events: ClaudeEvent[]): string | null {
  for (const evt of events) {
    if (evt.type !== "user") continue;
    const content = evt.message?.content;
    const text =
      typeof content === "string"
        ? content
        : typeof evt.content === "string"
          ? (evt.content as string)
          : null;
    const t = text?.trim();
    if (t && !t.startsWith("<")) return t.length > 60 ? `${t.slice(0, 60)}…` : t;
  }
  return null;
}

// ---- equality guards -------------------------------------------------------
// The poll loop and cache-then-revalidate flows re-fetch lists every few
// seconds. Re-applying an identical payload churns state identity (new array /
// object), which re-renders rows, re-sorts the in-focus list, and resets the
// transcript scroll. These compare incoming data to what's on screen so we
// only commit real changes.
function sameIds(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const s = new Set(a);
  return b.every((x) => s.has(x));
}
function sameTitles(a: TitleMap, b: TitleMap): boolean {
  const ak = Object.keys(a);
  if (ak.length !== Object.keys(b).length) return false;
  return ak.every((k) => a[k] === b[k]);
}
// Active rows render from membership + title + folder; lastActiveAt only feeds
// ordering (handled separately), so ignore it here to avoid per-tick churn.
function sameActive(a: ActiveMap, b: ActiveMap): boolean {
  const ak = Object.keys(a);
  if (ak.length !== Object.keys(b).length) return false;
  return ak.every(
    (k) => b[k] && a[k].title === b[k].title && a[k].folder === b[k].folder,
  );
}
function sameSessions(a: SessionSummary[], b: SessionSummary[]): boolean {
  if (a.length !== b.length) return false;
  return a.every(
    (s, i) =>
      s.sessionId === b[i].sessionId &&
      s.title === b[i].title &&
      s.modified === b[i].modified,
  );
}
function eventsKey(e: ClaudeEvent[]): string {
  if (!e.length) return "0";
  const last = e[e.length - 1] as ClaudeEvent & { uuid?: string };
  return `${e.length}:${last.timestamp ?? ""}:${last.uuid ?? ""}`;
}
function sameEvents(a: ClaudeEvent[], b: ClaudeEvent[]): boolean {
  return a.length === b.length && eventsKey(a) === eventsKey(b);
}

// Placeholder rows shown while a list loads, sized like real rows so the
// container reserves its height and content doesn't jump in on arrival.
function SkeletonRows({ count = 5 }: { count?: number }) {
  return (
    <div className="skeletons" aria-hidden>
      {Array.from({ length: count }, (_, i) => (
        <div className="skeleton-row" key={i}>
          <div className="skeleton-dot" />
          <div className="skeleton-main">
            <div className="skeleton-line w60" />
            <div className="skeleton-line w40" />
          </div>
        </div>
      ))}
    </div>
  );
}

function fmtAgo(ms: number): string {
  const s = Math.floor((Date.now() - ms) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

// One session row: a focus toggle (filled = active/in-focus, hollow = done) on
// the left, title + meta (running / waiting / timestamp), delete on the right.
function SessionRow({
  s,
  active,
  doing,
  folderLabel,
  colorClass,
  href,
  onOpen,
  onToggle,
  onRemove,
  onRename,
}: {
  s: { sessionId: string; title: string; modified: number };
  active: boolean;
  doing: boolean;
  folderLabel?: string;
  colorClass?: string;
  href: string;
  onOpen: () => void;
  onToggle: () => void;
  onRemove: () => void;
  onRename: (title: string) => void;
}) {
  const waiting = active && !doing;
  // doing = solid dot (live), waiting = clock (your turn), done = check.
  const stateIcon = doing ? faCircle : waiting ? faClock : faCircleCheck;
  const stateTitle = doing
    ? "Doing — click to mark done"
    : waiting
      ? "Waiting — click to mark done"
      : "Done — click to bring to focus";

  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(s.title);
  const startEdit = () => {
    setDraft(s.title);
    setEditing(true);
  };
  const commit = () => {
    setEditing(false);
    if (draft.trim() !== s.title) onRename(draft.trim());
  };

  return (
    <a
      href={editing ? undefined : href}
      className={`row session-row${doing ? " session-doing" : ""}${
        waiting ? " session-waiting" : ""
      }${colorClass ? ` ${colorClass}` : ""}`}
      onClick={(e) => {
        if (editing || isModifiedClick(e)) return; // let the browser open it
        e.preventDefault();
        onOpen();
      }}
    >
      <button
        className="icon-btn done-toggle"
        title={stateTitle}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          onToggle();
        }}
      >
        <FontAwesomeIcon icon={stateIcon} />
      </button>
      <div className="row-main">
        {editing ? (
          <input
            className="row-title-edit"
            value={draft}
            autoFocus
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
            }}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={commit}
            onKeyDown={(e) => {
              if (e.key === "Enter") commit();
              else if (e.key === "Escape") setEditing(false);
            }}
            placeholder="Session title"
          />
        ) : (
          <div className="row-title ellipsis">{s.title}</div>
        )}
        <div className="row-sub mono">
          {folderLabel ? <>{folderLabel} · </> : null}
          {doing ? (
            <span className="doing-tag">
              <FontAwesomeIcon icon={faSpinner} spin /> running
            </span>
          ) : waiting ? (
            <span className="waiting-tag">waiting</span>
          ) : (
            fmtAgo(s.modified)
          )}{" "}
          · {s.sessionId.slice(0, 8)}
        </div>
      </div>
      <button
        className="icon-btn row-edit"
        title="Rename session"
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          startEdit();
        }}
      >
        <FontAwesomeIcon icon={faPencil} />
      </button>
      <button
        className="icon-btn row-del"
        title="Delete session"
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          onRemove();
        }}
      >
        <FontAwesomeIcon icon={faXmark} />
      </button>
    </a>
  );
}

// Header breadcrumbs: project › session. The project crumb navigates back to
// the folder's session list; the session crumb is the conversation title and is
// editable inline (click to rename), mirroring SessionRow's rename pattern.
function Breadcrumbs({
  project,
  onProject,
  title,
  onRename,
}: {
  project: string;
  onProject: () => void;
  title: string;
  onRename?: (t: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(title);
  const startEdit = () => {
    if (!onRename) return;
    setDraft(title);
    setEditing(true);
  };
  const commit = () => {
    setEditing(false);
    if (onRename && draft.trim() !== title) onRename(draft.trim());
  };
  return (
    <div className="crumbs">
      <button className="crumb" title="Back to sessions" onClick={onProject}>
        {project}
      </button>
      <span className="crumb-sep">›</span>
      {editing ? (
        <input
          className="row-title-edit crumb-edit"
          value={draft}
          autoFocus
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === "Enter") commit();
            else if (e.key === "Escape") setEditing(false);
          }}
          placeholder="Conversation title"
        />
      ) : (
        <span
          className={`crumb-current ellipsis${onRename ? " editable" : ""}`}
          title={onRename ? "Rename conversation" : undefined}
          onClick={startEdit}
        >
          {title}
        </span>
      )}
    </div>
  );
}

export default function ClaudeManager() {
  const [view, setView] = useState<View>("folders");
  const [folders, setFolders] = useState<FolderPath[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [gitOpen, setGitOpen] = useState(false);
  const [live, setLive] = useState<LiveSession[]>([]);
  const [active, setActive] = useState<ActiveMap>({});
  const [titles, setTitles] = useState<TitleMap>({});
  const [folderMeta, setFolderMeta] = useState<FolderMetaMap>({});
  const [colorPickerFor, setColorPickerFor] = useState<string | null>(null);

  const [usageOpen, setUsageOpen] = useState(false);
  const [modelMenuOpen, setModelMenuOpen] = useState(false);
  // Read the stored model in the initializer so the first render already has
  // it — otherwise the context % would compute against the default model and
  // visibly correct itself a tick later.
  const [model, setModel] = useState(() => {
    if (typeof window === "undefined") return DEFAULT_MODEL;
    try {
      return localStorage.getItem("claudia-model") || DEFAULT_MODEL;
    } catch {
      return DEFAULT_MODEL;
    }
  });
  const [usage, setUsage] = useState<UsageData | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);
  const [usageError, setUsageError] = useState<string | null>(null);

  // Mute for the "a session finished" chime. Read in the initializer so the
  // toolbar button shows the right icon on first paint.
  const [muted, setMuted] = useState(() => {
    if (typeof window === "undefined") return false;
    try {
      return localStorage.getItem("claudia-sound-muted") === "true";
    } catch {
      return false;
    }
  });
  // Logo hover popover listing the in-focus sessions and their state.
  const [logoPopOpen, setLogoPopOpen] = useState(false);

  const [folder, setFolder] = useState<string | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [events, setEvents] = useState<ClaudeEvent[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [input, setInput] = useState("");
  const [queue, setQueue] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const jobIdRef = useRef<string | null>(null);
  // Monotonic "which stream owns the chat view" token. Every navigation or new
  // turn bumps it; async results (stream frames, cached paints, delta loads)
  // carry the generation they were started under and are dropped if it no longer
  // matches. Without this a background reader from a session you've left keeps
  // calling setEvents/setStreaming into whatever chat is now on screen — frames
  // land in the wrong chat, or in a fresh "new session".
  const streamGenRef = useRef(0);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  // Finish-sound plumbing. prevLiveRef holds last tick's running set so the poll
  // can spot sessions that just stopped; seededLiveRef skips the very first tick
  // (so a refresh mid-run doesn't false-fire). The audio element is created
  // lazily. view/session/muted are mirrored into refs because the poll effect
  // runs once (empty deps) and would otherwise read stale values.
  const prevLiveRef = useRef<Set<string>>(new Set());
  const seededLiveRef = useRef(false);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const viewRef = useRef(view);
  const sessionIdRef = useRef(sessionId);
  const mutedRef = useRef(muted);
  viewRef.current = view;
  sessionIdRef.current = sessionId;
  mutedRef.current = muted;
  // Delayed-close timer so moving the cursor from the logo into the popover
  // (across the small gap) doesn't dismiss it.
  const logoPopTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Bumped on every optimistic active/title edit. A meta poll that started
  // before the edit captures the old value; comparing generations lets us drop
  // its stale result instead of clobbering the optimistic update.
  const metaGenRef = useRef(0);
  // Frozen display order for the in-focus list so existing rows keep their
  // place across polls; new sessions prepend, removed ones drop out.
  const activeOrderRef = useRef<string[]>([]);
  // Skip the first URL-sync run so it doesn't wipe the query string before the
  // restore-from-URL effect has read it.
  const skipUrlSync = useRef(true);
  // Previous nav target — lets the mirror effect push (real navigation) vs
  // replace (in-place change, e.g. a new session getting its assigned id).
  const lastNav = useRef<{ view: View; folder: string | null }>({
    view: "folders",
    folder: null,
  });

  const refreshFolders = useCallback(async () => {
    setFolders(await getFolders());
  }, []);

  // Auto-focus the composer whenever a chat opens (new or existing session).
  useEffect(() => {
    if (view === "chat") inputRef.current?.focus();
  }, [view, sessionId]);

  // Reflect what's open in the tab title.
  useEffect(() => {
    let title = "claudia";
    if (view === "chat" && folder) {
      const name = sessionId
        ? (titleFromEvents(events) ?? "Session")
        : "New session";
      title = `${shortName(folder)} — ${name}`;
    } else if (view === "sessions" && folder) {
      title = `${shortName(folder)} — Sessions`;
    }
    document.title = title;
  }, [view, folder, sessionId, events]);

  // Mirror navigation state into the URL. Push a real history entry when the
  // view or folder changed (navigation, so back/forward can traverse), but
  // replace for in-place changes like a new session receiving its assigned id.
  useEffect(() => {
    const navChanged =
      lastNav.current.view !== view || lastNav.current.folder !== folder;
    lastNav.current = { view, folder };

    if (skipUrlSync.current) {
      skipUrlSync.current = false;
      return;
    }
    const sp = new URLSearchParams();
    if (folder && (view === "sessions" || view === "chat")) sp.set("folder", folder);
    if (view === "chat") sp.set("session", sessionId ?? "new");
    const qs = sp.toString();
    const target = qs ? `/?${qs}` : "/";
    if (target === window.location.pathname + window.location.search) return;
    if (navChanged) window.history.pushState(null, "", target);
    else window.history.replaceState(null, "", target);
  }, [view, folder, sessionId]);

  // Play the finish chime, creating the audio element on first use. Multiple
  // simultaneous finishes collapse to one play (rewinds to start). Autoplay
  // rejections are ignored — in practice the user has clicked by now.
  const playFinishSound = useCallback(() => {
    try {
      let a = audioRef.current;
      if (!a) {
        a = new Audio("/finished.mp3");
        a.volume = 0.5;
        audioRef.current = a;
      }
      a.currentTime = 0;
      void a.play().catch(() => {});
    } catch {
      /* no audio available */
    }
  }, []);

  // Poll the live ("doing") set and the active set on every view — not just the
  // folder/session lists — so the logo's "in focus" badge is correct on the chat
  // page and immediately after a refresh into any page, not only once you visit
  // home. Both payloads are small. Runs once on mount and every 4s thereafter.
  useEffect(() => {
    let alive = true;
    const tick = () => {
      getLive()
        .then((l) => {
          if (!alive) return;
          const nextIds = new Set(l.map((p) => p.sessionId));
          if (!seededLiveRef.current) {
            // First tick after mount only establishes the baseline.
            seededLiveRef.current = true;
          } else {
            // Sessions present last tick but gone now just finished. Play the
            // chime unless you're already watching that exact session focused —
            // you'd see it finish, so the sound would be noise.
            const finished = [...prevLiveRef.current].filter(
              (id) => !nextIds.has(id),
            );
            const worthSound = finished.some(
              (id) =>
                !(
                  viewRef.current === "chat" &&
                  sessionIdRef.current === id &&
                  document.visibilityState === "visible"
                ),
            );
            if (worthSound && !mutedRef.current) playFinishSound();
          }
          prevLiveRef.current = nextIds;
          setLive((prev) =>
            sameIds(
              prev.map((p) => p.sessionId),
              l.map((p) => p.sessionId),
            )
              ? prev
              : l,
          );
        })
        .catch(() => {});
      // Snapshot the mutation generation; if an optimistic edit lands while this
      // request is in flight, the result is stale — drop it.
      const gen = metaGenRef.current;
      getSessionMeta()
        .then((m) => {
          if (!alive || metaGenRef.current !== gen) return;
          setActive((prev) => (sameActive(prev, m.active) ? prev : m.active));
          setTitles((prev) => (sameTitles(prev, m.titles) ? prev : m.titles));
        })
        .catch(() => {});
    };
    tick();
    const id = setInterval(tick, 4000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [playFinishSound]);

  // ---- usage: fresh on load, then auto-refresh every 10 minutes ----
  const refreshUsage = useCallback(async (force: boolean) => {
    setUsageLoading(true);
    setUsageError(null);
    try {
      setUsage(await getUsage(force));
    } catch (e) {
      setUsageError(e instanceof Error ? e.message : String(e));
    } finally {
      setUsageLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshUsage(true);
    const id = setInterval(() => void refreshUsage(true), 10 * 60 * 1000);
    return () => clearInterval(id);
  }, [refreshUsage]);

  // Fold the event stream once; the renderer draws it and the header chip reads
  // the task list out of it.
  const items = useMemo(() => foldEvents(events), [events]);
  // Context-window occupancy for this session, derived from the latest usage.
  const ctx = useMemo(() => contextOf(events, model), [events, model]);
  const tasks =
    (items.find((it) => it.kind === "tasks") as
      | Extract<DisplayItem, { kind: "tasks" }>
      | undefined)?.tasks ?? [];

  // "doing" = a live job is streaming. "active" = in the home set (sessionMeta).
  const liveIds = useMemo(() => new Set(live.map((l) => l.sessionId)), [live]);
  // Home "in focus" list. Order is frozen in a ref so a 4s poll that only bumps
  // lastActiveAt never reshuffles rows under the cursor: existing rows keep
  // their place, newly-active sessions prepend (most recent first), removed
  // ones drop out.
  const activeList = useMemo(() => {
    const ids = Object.keys(active);
    const present = new Set(ids);
    const kept = activeOrderRef.current.filter((id) => present.has(id));
    const known = new Set(kept);
    const added = ids
      .filter((id) => !known.has(id))
      .sort((a, b) => active[b].lastActiveAt - active[a].lastActiveAt);
    const order = [...added, ...kept];
    activeOrderRef.current = order;
    return order.map((sessionId) => ({ sessionId, ...active[sessionId] }));
  }, [active]);

  // ---- chat transport ----
  // Abort the current stream and bump the generation so any frames still in
  // flight from it are ignored. Call before starting a new stream or leaving
  // the chat view.
  const invalidateStream = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    streamGenRef.current++;
  }, []);

  // Apply a stream message from a job (live turn or a reconnect snapshot).
  // `gen` is the stream generation this handler was bound to; if the view has
  // since moved on (navigation, new turn) the message belongs to a session we
  // left, so drop it rather than writing it into the current chat.
  const applyMsg = useCallback((gen: number, msg: ChatStreamMessage) => {
    if (gen !== streamGenRef.current) return;
    switch (msg.kind) {
      case "snapshot":
        jobIdRef.current = msg.jobId;
        // Reconnect snapshot is authoritative, but skip the swap if it matches
        // what we already painted from cache — avoids a scroll reset / flashing
        // the optimistic bubble we just appended.
        setEvents((prev) => (sameEvents(prev, msg.events) ? prev : msg.events));
        if (msg.sessionId) setSessionId(msg.sessionId);
        setLoading(false);
        setStreaming(msg.status === "running");
        if (msg.status === "error" && msg.error) setError(msg.error);
        break;
      case "event":
        setEvents((prev) => [...prev, msg.event]);
        break;
      case "session-id":
        setSessionId(msg.sessionId);
        break;
      case "error":
        setError(msg.message);
        break;
      case "done":
        setStreaming(false);
        break;
    }
  }, []);

  // Reconnect to a running job for this session, else load its transcript.
  const attachOrLoad = useCallback(
    async (f: string, id: string) => {
      // Take ownership of the chat view: abort any prior stream and claim a
      // fresh generation. Every state write below is gated on it still being
      // current, so a rapid open A → open B can't paint A's transcript into B.
      invalidateStream();
      const gen = streamGenRef.current;
      setError(null);
      // Paint from cache first — before any network — so a cached session
      // appears instantly. The running-check and delta fetch run behind it.
      const cached = await getCachedTranscript(id);
      if (gen !== streamGenRef.current) return;
      if (cached) {
        setEvents(cached.events);
        setLoading(false);
      } else {
        setLoading(true);
      }
      const ac = new AbortController();
      abortRef.current = ac;
      try {
        const running = await subscribeChat(id, (m) => applyMsg(gen, m), ac.signal);
        if (gen !== streamGenRef.current) return;
        if (running) {
          setStreaming(true); // snapshot replaces events with live state
        } else {
          if (abortRef.current === ac) abortRef.current = null;
          const res = await loadSessionDelta(f, id, cached?.size ?? 0);
          if (gen !== streamGenRef.current) return;
          const events =
            !cached || res.reset
              ? res.events
              : res.events.length
                ? [...cached.events, ...res.events]
                : cached.events;
          if (!cached || res.reset || res.events.length)
            setEvents((prev) => (sameEvents(prev, events) ? prev : events));
          setLoading(false);
          void putCachedTranscript({
            sessionId: id,
            events,
            size: res.size,
            modified: res.modified,
          });
        }
      } catch (e) {
        if (gen !== streamGenRef.current) return;
        if (abortRef.current === ac) abortRef.current = null;
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      }
    },
    [applyMsg, invalidateStream],
  );

  // Restore view/folder/session from a URL query string. Used on load and on
  // browser back/forward (popstate). Handles the home case so navigating back
  // to "/" resets out of a deeper view.
  const applyUrl = useCallback(
    (search: string) => {
      invalidateStream();
      const sp = new URLSearchParams(search);
      const f = sp.get("folder");
      const sess = sp.get("session");
      if (!f) {
        setSessionId(null);
        setEvents([]);
        setStreaming(false);
        setFolder(null);
        setView("folders");
        return;
      }
      setFolder(f);
      if (sess === "new") {
        setSessionId(null);
        setEvents([]);
        setStreaming(false);
        setView("chat");
      } else if (sess) {
        setSessionId(sess);
        setView("chat");
        void attachOrLoad(f, sess);
      } else {
        setSessionId(null);
        setView("sessions");
        const cached = readSessionListCache(f);
        if (cached) setSessions(cached);
        else setLoading(true);
        getSessions(f)
          .then((fresh) => {
            setSessions((prev) => (sameSessions(prev, fresh) ? prev : fresh));
            writeSessionListCache(f, fresh);
          })
          .finally(() => setLoading(false));
      }
    },
    [attachOrLoad, invalidateStream],
  );

  // On load: warm caches, restore from the URL, and listen for back/forward.
  useEffect(() => {
    warmTranscriptCache();
    void refreshFolders();
    getFolderMeta()
      .then(setFolderMeta)
      .catch(() => {});
    applyUrl(window.location.search);

    const onPop = () => {
      skipUrlSync.current = true; // popstate already changed the URL; don't re-push
      applyUrl(window.location.search);
    };
    window.addEventListener("popstate", onPop);
    return () => {
      window.removeEventListener("popstate", onPop);
      abortRef.current?.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- folders ----
  const onAddFolder = async (path: string) => {
    setFolders(await apiAddFolder(path));
    setPickerOpen(false);
  };
  const onRemoveFolder = async (path: string) => {
    setFolders(await apiRemoveFolder(path));
  };

  // Set a folder's theme color (or clear it). Optimistic, then confirm.
  const onSetFolderColor = async (f: string, color: string | null) => {
    setFolderMeta((m) => {
      const next = { ...m };
      if (color) next[f] = { color: color as (typeof FOLDER_COLORS)[number] };
      else delete next[f];
      return next;
    });
    try {
      setFolderMeta(await apiSetFolderColor(f, color));
    } catch {
      /* keep optimistic value; next load re-syncs */
    }
  };

  // The CSS classes that tint a row/pane for a folder's chosen color, or "".
  const tintClass = (f: string | null | undefined): string =>
    f && folderMeta[f] ? `folder-tint folder-color-${folderMeta[f].color}` : "";

  const openFolder = async (f: string) => {
    setFolder(f);
    setView("sessions");
    // Render the last known list instantly, then revalidate in the background.
    const cached = readSessionListCache(f);
    if (cached) {
      setSessions(cached);
      setLoading(false);
    } else {
      setSessions([]);
      setLoading(true);
    }
    try {
      const fresh = await getSessions(f);
      setSessions((prev) => (sameSessions(prev, fresh) ? prev : fresh));
      writeSessionListCache(f, fresh);
    } finally {
      setLoading(false);
    }
  };

  // ---- sessions ----
  const openSession = async (f: string, id: string) => {
    setFolder(f);
    setSessionId(id);
    setEvents([]);
    setQueue([]);
    setError(null);
    setView("chat");
    await attachOrLoad(f, id);
  };

  const newSession = (f: string) => {
    invalidateStream();
    jobIdRef.current = null;
    setFolder(f);
    setSessionId(null);
    setEvents([]);
    setQueue([]);
    setError(null);
    setStreaming(false);
    setView("chat");
  };

  // Bring a session to focus (done → active). Optimistic, then confirm.
  const activate = async (id: string, f: string, title: string) => {
    metaGenRef.current++;
    setActive((m) => ({ ...m, [id]: { folder: f, title, lastActiveAt: Date.now() } }));
    try {
      setActive(await setSessionActive(id, true, f, title));
    } catch {
      /* next poll re-syncs */
    }
  };

  // Mark a session done (active → done; leaves the home list). Optimistic.
  const markDone = async (id: string) => {
    metaGenRef.current++;
    setActive((m) => {
      const next = { ...m };
      delete next[id];
      return next;
    });
    try {
      setActive(await setSessionActive(id, false));
    } catch {
      /* next poll re-syncs */
    }
  };

  // The one toggle used by every session row, on home and in the folder list:
  // flip between active (in focus) and done based on current state.
  const toggleActive = (id: string, f: string, title: string) =>
    active[id] ? markDone(id) : activate(id, f, title);

  // Rename a session. Optimistic on the titles map (which always wins at render
  // time); an empty title clears the override back to the derived title.
  const renameSession = async (id: string, title: string) => {
    metaGenRef.current++;
    setTitles((m) => {
      const next = { ...m };
      if (title) next[id] = title;
      else delete next[id];
      return next;
    });
    try {
      setTitles(await setSessionTitle(id, title));
    } catch {
      /* next poll re-syncs */
    }
  };

  // Permanently delete a session's transcript (gone from Claude too).
  const removeSession = async (f: string, id: string) => {
    if (
      !window.confirm(
        "Delete this session? This permanently removes its transcript.",
      )
    )
      return;
    const next = sessions.filter((s) => s.sessionId !== id);
    setSessions(next);
    metaGenRef.current++;
    setActive((m) => {
      const n = { ...m };
      delete n[id];
      return n;
    });
    writeSessionListCache(f, next);
    void deleteCachedTranscript(id);
    try {
      await apiDeleteSession(f, id);
    } catch (e) {
      // The optimistic removal failed server-side; refetch will bring the row
      // back, so tell the user why instead of letting it silently reappear.
      window.alert(
        `Couldn't delete this session: ${
          e instanceof Error ? e.message : String(e)
        }`,
      );
      const fresh = await getSessions(f);
      setSessions((prev) => (sameSessions(prev, fresh) ? prev : fresh));
      writeSessionListCache(f, fresh);
    }
  };

  // ---- chat ----
  // Detach from the stream without killing the job (refresh / navigate away).
  const detach = useCallback(() => {
    invalidateStream();
    setStreaming(false);
  }, [invalidateStream]);

  // Explicitly kill the running job (Stop button / Esc), clear the queue, detach.
  const stopStream = useCallback(() => {
    const jobId = jobIdRef.current;
    if (jobId) void stopChat({ jobId });
    else if (sessionId) void stopChat({ session: sessionId });
    setQueue([]);
    detach();
  }, [detach, sessionId]);

  // Remember the chosen model across reloads (read lazily in the initializer).
  const changeModel = (id: string) => {
    setModel(id);
    try {
      localStorage.setItem("claudia-model", id);
    } catch {
      /* ignore */
    }
  };

  // Toggle (and persist) the finish chime mute.
  const toggleMuted = () => {
    setMuted((m) => {
      const next = !m;
      try {
        localStorage.setItem("claudia-sound-muted", String(next));
      } catch {
        /* ignore */
      }
      return next;
    });
  };

  // Esc stops the current generation, like the Stop button.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && streaming) {
        e.preventDefault();
        stopStream();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [streaming, stopStream]);

  // Send arbitrary text as a turn (used by the composer and by question answers).
  const sendText = async (text: string) => {
    const prompt = text.trim();
    if (!prompt || streaming || !folder) return;

    const userEvent: ClaudeEvent = {
      type: "user",
      message: { role: "user", content: prompt },
      timestamp: new Date().toISOString(),
    };
    setEvents((prev) => [...prev, userEvent]); // optimistic; snapshot reconciles
    setError(null);
    setStreaming(true);

    // Claim a fresh generation for this turn so any lingering frames from a
    // prior stream are ignored, and so a navigation mid-turn stops us flipping
    // this session's streaming flag from a turn that no longer owns the view.
    streamGenRef.current++;
    const gen = streamGenRef.current;
    const ac = new AbortController();
    abortRef.current = ac;

    try {
      await startChat(
        { folder, sessionId, prompt, model, signal: ac.signal },
        (m) => applyMsg(gen, m),
      );
    } finally {
      if (gen === streamGenRef.current) {
        if (abortRef.current === ac) abortRef.current = null;
        setStreaming(false);
      }
    }
  };

  // Composer submit: send now if idle, otherwise queue it.
  const submitInput = () => {
    const t = input.trim();
    if (!t || !folder) return;
    setInput("");
    if (streaming) setQueue((q) => [...q, t]);
    else void sendText(t);
  };

  const cancelQueued = (index: number) =>
    setQueue((q) => q.filter((_, i) => i !== index));

  // Compact the conversation: /compact is a normal turn the CLI honours even in
  // headless mode (it summarises history and continues the same session).
  const compactNow = () => {
    if (streaming || !folder || !sessionId) return;
    void sendText("/compact");
  };

  // When a turn finishes, send all queued messages together as one.
  useEffect(() => {
    if (streaming || queue.length === 0) return;
    const combined = queue.join("\n\n");
    setQueue([]);
    void sendText(combined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [streaming, queue]);

  // ---- views ----
  const goHome = () => {
    detach();
    setView("folders");
  };
  // Badge counts the in-focus sessions (doing + waiting) so the logo carries a
  // running "how many sessions need me" number across every view.
  const homeBadgeCount = activeList.length;
  // In-focus sessions for the hover popover, doing (live) sorted before waiting.
  const logoPopList = [...activeList].sort(
    (a, b) =>
      Number(liveIds.has(b.sessionId)) - Number(liveIds.has(a.sessionId)),
  );
  const openLogoPop = () => {
    if (logoPopTimer.current) clearTimeout(logoPopTimer.current);
    setLogoPopOpen(true);
  };
  const closeLogoPopSoon = () => {
    if (logoPopTimer.current) clearTimeout(logoPopTimer.current);
    logoPopTimer.current = setTimeout(() => setLogoPopOpen(false), 150);
  };
  const homeLogo = (
    <span
      className="brand-logo-wrap"
      onMouseEnter={openLogoPop}
      onMouseLeave={closeLogoPopSoon}
    >
      <span className="brand-logo-click" onClick={goHome} title="Home">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="brand-logo" src="/claudia.webp" alt="claudia" />
        {homeBadgeCount > 0 ? (
          <span className="brand-badge">{homeBadgeCount}</span>
        ) : null}
      </span>
      {logoPopOpen && (
        <div
          className="logo-pop"
          onMouseEnter={openLogoPop}
          onMouseLeave={closeLogoPopSoon}
        >
          <div className="logo-pop-head">In focus</div>
          <div className="logo-pop-body">
            {logoPopList.length === 0 ? (
              <div className="logo-pop-empty">No sessions in focus</div>
            ) : (
              logoPopList.map((a) => {
                const doing = liveIds.has(a.sessionId);
                return (
                  <button
                    key={a.sessionId}
                    className="logo-pop-item"
                    onClick={() => {
                      setLogoPopOpen(false);
                      void openSession(a.folder, a.sessionId);
                    }}
                    title={doing ? "Doing — running now" : "Waiting — your turn"}
                  >
                    <FontAwesomeIcon
                      icon={doing ? faCircle : faClock}
                      className={`lp-ic ${doing ? "lp-doing" : "lp-waiting"}`}
                    />
                    <span className="lp-title ellipsis">
                      {titles[a.sessionId] ?? a.title}
                    </span>
                    <span className="lp-folder mono">{shortName(a.folder)}</span>
                  </button>
                );
              })
            )}
          </div>
        </div>
      )}
    </span>
  );

  const modelLabel = MODELS.find((m) => m.id === model)?.label ?? model;
  const modelChooser = (
    <div className="model-pick">
      <button
        className="icon-btn"
        title={`Model: ${modelLabel}`}
        aria-label={`Model: ${modelLabel}`}
        onClick={() => setModelMenuOpen((v) => !v)}
      >
        <FontAwesomeIcon icon={faMicrochip} />
      </button>
      {modelMenuOpen && (
        <>
          <div
            className="color-popup-backdrop"
            onClick={() => setModelMenuOpen(false)}
          />
          <div className="model-menu">
            {MODELS.map((m) => (
              <button
                key={m.id}
                className={`model-menu-item${m.id === model ? " on" : ""}`}
                onClick={() => {
                  changeModel(m.id);
                  setModelMenuOpen(false);
                }}
              >
                <FontAwesomeIcon
                  icon={faCheck}
                  className="model-menu-check"
                />
                {m.label}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );

  const soundBtn = (
    <button
      className="icon-btn"
      onClick={toggleMuted}
      title={muted ? "Finish sound off — click to unmute" : "Finish sound on — click to mute"}
      aria-label={muted ? "Unmute finish sound" : "Mute finish sound"}
    >
      <FontAwesomeIcon icon={muted ? faVolumeXmark : faVolumeHigh} />
    </button>
  );

  const sessionPct = usage?.limits.find((l) => /session/i.test(l.name))?.percentUsed;
  const usageBtn = (
    <button
      className="btn ghost usage-btn"
      onClick={() => setUsageOpen(true)}
      title="Usage stats"
    >
      <FontAwesomeIcon icon={faChartColumn} />{" "}
      {usageLoading && sessionPct == null ? "…" : sessionPct != null ? `${sessionPct}%` : "Usage"}
    </button>
  );

  // Per-session context meter; clicking it runs /compact. Past ~80% it pulses
  // and shows the compress icon as a "compact recommended" hint.
  const ctxPct = ctx ? Math.round(ctx.pct) : 0;
  const ctxHot = ctx ? ctx.pct >= COMPACT_AUTO_PCT : false;
  const ctxWarm = ctx ? ctx.pct >= COMPACT_SUGGEST_PCT : false;
  // Always rendered (placeholder "—" until a context reading exists) so the pill
  // reserves its width instead of popping into the toolbar after the first turn.
  const ctxChip = (
    <button
      className={`btn ghost ctx-chip ${ctxHot ? "hot" : ctxWarm ? "warm" : ""}`}
      onClick={compactNow}
      disabled={!ctx || streaming || !sessionId}
      title={
        ctx
          ? `Context: ${ctx.tokens.toLocaleString()} / ${ctx.window.toLocaleString()} tokens (${ctxPct}%)${
              ctxWarm ? " — compact recommended" : ""
            }. Click to /compact.`
          : "Context usage (no reading yet)"
      }
    >
      <FontAwesomeIcon icon={ctxWarm ? faCompress : faGaugeHigh} />{" "}
      {ctx ? `${ctxPct}%` : "—"}
    </button>
  );

  return (
    <div className="cm">
      {view === "folders" && (
        <div className="pane">
          <div className="toolbar">
            <div className="brand-wrap">
              {homeLogo}
              <h1 className="brand">claudia</h1>
            </div>
            <div className="spacer" />
            <button className="btn accent" onClick={() => setPickerOpen(true)}>
              <FontAwesomeIcon icon={faFolderPlus} /> Add folder
            </button>
            {modelChooser}
            {soundBtn}
            {usageBtn}
          </div>
          <div className="scroll">
            {activeList.length > 0 && (
              <div className="live-section">
                <div className="git-section-title">In focus</div>
                {activeList.map((a) => (
                  <SessionRow
                    key={a.sessionId}
                    s={{
                      sessionId: a.sessionId,
                      title: titles[a.sessionId] ?? a.title,
                      modified: a.lastActiveAt,
                    }}
                    active
                    doing={liveIds.has(a.sessionId)}
                    folderLabel={shortName(a.folder)}
                    colorClass={tintClass(a.folder)}
                    href={hrefFor(a.folder, a.sessionId)}
                    onOpen={() => openSession(a.folder, a.sessionId)}
                    onToggle={() => void toggleActive(a.sessionId, a.folder, a.title)}
                    onRemove={() => void removeSession(a.folder, a.sessionId)}
                    onRename={(t) => void renameSession(a.sessionId, t)}
                  />
                ))}
              </div>
            )}
            <div className="folder-section">
              <div className="git-section-title">Folders</div>
              {folders.length === 0 ? (
                <div className="muted center pad">
                  No folders yet. Add one above to see its Claude sessions.
                </div>
              ) : (
                folders.map((f) => (
                  <a
                    key={f}
                    href={hrefFor(f)}
                    className={`row${tintClass(f) ? ` ${tintClass(f)}` : ""}`}
                    onClick={(e) => {
                      if (isModifiedClick(e)) return; // let the browser open it
                      e.preventDefault();
                      void openFolder(f);
                    }}
                  >
                    <span className="dir-icon">
                      <FontAwesomeIcon icon={faFolder} />
                    </span>
                    <div className="row-main">
                      <div className="row-title">{shortName(f)}</div>
                      <div className="row-sub mono">{f}</div>
                    </div>
                    <div
                      className="folder-color-pick"
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                      }}
                    >
                      <button
                        className={`color-dot${
                          folderMeta[f]?.color
                            ? ` folder-color-${folderMeta[f].color}`
                            : " color-dot-none"
                        }`}
                        title="Folder color"
                        aria-label="Choose folder color"
                        onClick={() =>
                          setColorPickerFor(colorPickerFor === f ? null : f)
                        }
                      />
                      {colorPickerFor === f && (
                        <>
                          <div
                            className="color-popup-backdrop"
                            onClick={() => setColorPickerFor(null)}
                          />
                          <div className="color-popup">
                            {FOLDER_COLORS.map((c) => (
                              <button
                                key={c}
                                className={`swatch folder-color-${c}${
                                  folderMeta[f]?.color === c ? " on" : ""
                                }`}
                                title={c}
                                aria-label={`Set ${c}`}
                                onClick={() => {
                                  void onSetFolderColor(f, c);
                                  setColorPickerFor(null);
                                }}
                              />
                            ))}
                            <button
                              className={`swatch swatch-none${
                                folderMeta[f]?.color ? "" : " on"
                              }`}
                              title="No color"
                              aria-label="Clear color"
                              onClick={() => {
                                void onSetFolderColor(f, null);
                                setColorPickerFor(null);
                              }}
                            />
                          </div>
                        </>
                      )}
                    </div>
                    <button
                      className="icon-btn"
                      title="New session"
                      aria-label="New session"
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        newSession(f);
                      }}
                    >
                      <FontAwesomeIcon icon={faPlus} />
                    </button>
                    <button
                      className="icon-btn"
                      title="Remove folder"
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        void onRemoveFolder(f);
                      }}
                    >
                      <FontAwesomeIcon icon={faXmark} />
                    </button>
                  </a>
                ))
              )}
            </div>
          </div>
          {pickerOpen && (
            <FolderPicker onAdd={onAddFolder} onClose={() => setPickerOpen(false)} />
          )}
        </div>
      )}

      {view === "sessions" && folder && (
        <div className={`pane${tintClass(folder) ? ` ${tintClass(folder)}` : ""}`}>
          <div className="toolbar">
            {homeLogo}
            <button className="icon-btn" onClick={() => setView("folders")}>
              <FontAwesomeIcon icon={faArrowLeft} />
            </button>
            <div className="title">{shortName(folder)}</div>
            <div className="spacer" />
            <button className="btn accent" onClick={() => newSession(folder)}>
              <FontAwesomeIcon icon={faPlus} /> New session
            </button>
            {soundBtn}
            {usageBtn}
          </div>
          <div className="scroll">
            {loading ? (
              <SkeletonRows count={6} />
            ) : sessions.length === 0 ? (
              <div className="muted center pad">No sessions in this folder yet.</div>
            ) : (
              sessions.map((s) => {
                const isActive = !!active[s.sessionId];
                // titles map wins so an optimistic rename shows before refetch.
                const title = titles[s.sessionId] ?? s.title;
                return (
                  <SessionRow
                    key={s.sessionId}
                    s={{ ...s, title }}
                    active={isActive}
                    doing={liveIds.has(s.sessionId)}
                    colorClass={tintClass(folder)}
                    href={hrefFor(folder, s.sessionId)}
                    onOpen={() => openSession(folder, s.sessionId)}
                    onToggle={() => void toggleActive(s.sessionId, folder, title)}
                    onRemove={() => void removeSession(folder, s.sessionId)}
                    onRename={(t) => void renameSession(s.sessionId, t)}
                  />
                );
              })
            )}
          </div>
        </div>
      )}

      {view === "chat" && folder && (
        <div className={`pane${tintClass(folder) ? ` ${tintClass(folder)}` : ""}`}>
          <div className="toolbar">
            {homeLogo}
            <Breadcrumbs
              project={shortName(folder)}
              onProject={() => {
                detach();
                void openFolder(folder);
              }}
              title={
                sessionId
                  ? (titles[sessionId] ?? titleFromEvents(events) ?? "Session")
                  : "new"
              }
              onRename={
                sessionId
                  ? (t) => void renameSession(sessionId, t)
                  : undefined
              }
            />
            {tasks.length ? <TaskChip tasks={tasks} /> : null}
            <div className="spacer" />
            {streaming && (
              <button className="icon-btn is-danger" onClick={stopStream} title="Stop (Esc)">
                <FontAwesomeIcon icon={faCircleStop} />
              </button>
            )}
            {modelChooser}
            {ctxChip}
            {soundBtn}
            {usageBtn}
          </div>

          <div className="chat-scroll">
            {loading ? (
              <SkeletonRows count={4} />
            ) : (
              <StreamRenderer
                items={items}
                streaming={streaming}
                queue={queue}
                sessionId={sessionId}
                onAnswer={(t) => void sendText(t)}
                onCancelQueued={cancelQueued}
              />
            )}
          </div>

          {error && <div className="error mono">error: {error}</div>}

          <div className="composer">
            <textarea
              ref={inputRef}
              value={input}
              placeholder={
                sessionId ? "Reply to resume this session…" : "Start a new session…"
              }
              rows={1}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  submitInput();
                }
              }}
            />
            <button
              className="btn accent"
              disabled={!input.trim()}
              onClick={submitInput}
            >
              {streaming ? "Queue" : "Send"}
            </button>
            <button
              className={"btn" + (gitOpen ? " accent" : " ghost")}
              onClick={() => setGitOpen((v) => !v)}
              title="Git"
            >
              <FontAwesomeIcon icon={faCodeBranch} />
            </button>
          </div>
        </div>
      )}

      {usageOpen && (
        <UsagePanel
          data={usage}
          loading={usageLoading}
          error={usageError}
          onRefresh={() => void refreshUsage(true)}
          onClose={() => setUsageOpen(false)}
        />
      )}

      {gitOpen && folder && (
        <GitPanel folder={folder} onClose={() => setGitOpen(false)} />
      )}
    </div>
  );
}
