"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  ActiveMap,
  ChatStreamMessage,
  ClaudeEvent,
  FolderPath,
  LiveSession,
  SessionSummary,
} from "@/lib/types";
import type { UsageData } from "@/lib/usage";
import { contextOf, COMPACT_SUGGEST_PCT, COMPACT_AUTO_PCT } from "@/lib/context";
import {
  addFolder as apiAddFolder,
  deleteSession as apiDeleteSession,
  getActive,
  getFolders,
  getLive,
  getSessions,
  getUsage,
  loadSessionDelta,
  readSessionListCache,
  removeFolder as apiRemoveFolder,
  setSessionActive,
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
  faAnglesDown,
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
  faSpinner,
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
  onOpen,
  onToggle,
  onRemove,
}: {
  s: { sessionId: string; title: string; modified: number };
  active: boolean;
  doing: boolean;
  folderLabel?: string;
  onOpen: () => void;
  onToggle: () => void;
  onRemove: () => void;
}) {
  const waiting = active && !doing;
  return (
    <div
      className={`row session-row${doing ? " session-doing" : ""}${
        waiting ? " session-waiting" : ""
      }`}
      onClick={onOpen}
    >
      <button
        className="icon-btn done-toggle"
        title={active ? "Mark done" : "Bring to focus"}
        onClick={(e) => {
          e.stopPropagation();
          onToggle();
        }}
      >
        <FontAwesomeIcon icon={active ? faCircleCheck : faCircle} />
      </button>
      <div className="row-main">
        <div className="row-title ellipsis">{s.title}</div>
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
        className="icon-btn row-del"
        title="Delete session"
        onClick={(e) => {
          e.stopPropagation();
          onRemove();
        }}
      >
        <FontAwesomeIcon icon={faXmark} />
      </button>
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

  const [usageOpen, setUsageOpen] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const [model, setModel] = useState(DEFAULT_MODEL);
  const [usage, setUsage] = useState<UsageData | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);
  const [usageError, setUsageError] = useState<string | null>(null);

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
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
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

  // While browsing folders or a session list, poll the live ("doing") set and
  // the active set so the home "in focus" list, newly-sent sessions, and the
  // per-row badges stay fresh. Both payloads are small.
  useEffect(() => {
    if (view !== "folders" && view !== "sessions") return;
    let alive = true;
    const tick = () => {
      getLive()
        .then((l) => alive && setLive(l))
        .catch(() => {});
      getActive()
        .then((a) => alive && setActive(a))
        .catch(() => {});
    };
    tick();
    const id = setInterval(tick, 4000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [view]);

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
  // Home "in focus" list: the active set, most-recently-active first.
  const activeList = useMemo(
    () =>
      Object.entries(active)
        .map(([sessionId, e]) => ({ sessionId, ...e }))
        .sort((a, b) => b.lastActiveAt - a.lastActiveAt),
    [active],
  );

  // ---- chat transport ----
  // Apply a stream message from a job (live turn or a reconnect snapshot).
  const handleMsg = useCallback((msg: ChatStreamMessage) => {
    switch (msg.kind) {
      case "snapshot":
        jobIdRef.current = msg.jobId;
        setEvents(msg.events);
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
      setError(null);
      // Paint from cache first — before any network — so a cached session
      // appears instantly. The running-check and delta fetch run behind it.
      const cached = await getCachedTranscript(id);
      if (cached) {
        setEvents(cached.events);
        setLoading(false);
      } else {
        setLoading(true);
      }
      const ac = new AbortController();
      abortRef.current = ac;
      try {
        const running = await subscribeChat(id, handleMsg, ac.signal);
        if (running) {
          setStreaming(true); // snapshot replaces events with live state
        } else {
          abortRef.current = null;
          const res = await loadSessionDelta(f, id, cached?.size ?? 0);
          const events =
            !cached || res.reset
              ? res.events
              : res.events.length
                ? [...cached.events, ...res.events]
                : cached.events;
          if (!cached || res.reset || res.events.length) setEvents(events);
          setLoading(false);
          void putCachedTranscript({
            sessionId: id,
            events,
            size: res.size,
            modified: res.modified,
          });
        }
      } catch (e) {
        abortRef.current = null;
        setError(e instanceof Error ? e.message : String(e));
        setLoading(false);
      }
    },
    [handleMsg],
  );

  // Restore view/folder/session from a URL query string. Used on load and on
  // browser back/forward (popstate). Handles the home case so navigating back
  // to "/" resets out of a deeper view.
  const applyUrl = useCallback(
    (search: string) => {
      abortRef.current?.abort();
      abortRef.current = null;
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
            setSessions(fresh);
            writeSessionListCache(f, fresh);
          })
          .finally(() => setLoading(false));
      }
    },
    [attachOrLoad],
  );

  // On load: warm caches, restore from the URL, and listen for back/forward.
  useEffect(() => {
    warmTranscriptCache();
    void refreshFolders();
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
      setSessions(fresh);
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
    abortRef.current?.abort();
    abortRef.current = null;
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
    setActive((m) => ({ ...m, [id]: { folder: f, title, lastActiveAt: Date.now() } }));
    try {
      setActive(await setSessionActive(id, true, f, title));
    } catch {
      /* next poll re-syncs */
    }
  };

  // Mark a session done (active → done; leaves the home list). Optimistic.
  const markDone = async (id: string) => {
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
    setActive((m) => {
      const n = { ...m };
      delete n[id];
      return n;
    });
    writeSessionListCache(f, next);
    void deleteCachedTranscript(id);
    try {
      await apiDeleteSession(f, id);
    } catch {
      const fresh = await getSessions(f);
      setSessions(fresh);
      writeSessionListCache(f, fresh);
    }
  };

  // ---- chat ----
  // Detach from the stream without killing the job (refresh / navigate away).
  const detach = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setStreaming(false);
  }, []);

  // Explicitly kill the running job (Stop button / Esc), clear the queue, detach.
  const stopStream = useCallback(() => {
    const jobId = jobIdRef.current;
    if (jobId) void stopChat({ jobId });
    else if (sessionId) void stopChat({ session: sessionId });
    setQueue([]);
    detach();
  }, [detach, sessionId]);

  // Remember the chosen model across reloads (default Opus).
  useEffect(() => {
    try {
      const m = localStorage.getItem("claudia-model");
      if (m) setModel(m);
    } catch {
      /* ignore */
    }
  }, []);
  const changeModel = (id: string) => {
    setModel(id);
    try {
      localStorage.setItem("claudia-model", id);
    } catch {
      /* ignore */
    }
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

    const ac = new AbortController();
    abortRef.current = ac;

    try {
      await startChat({ folder, sessionId, prompt, model, signal: ac.signal }, handleMsg);
    } finally {
      if (abortRef.current === ac) abortRef.current = null;
      setStreaming(false);
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
  const homeLogo = (
    // eslint-disable-next-line @next/next/no-img-element
    <img
      className="brand-logo"
      src="/claudia.webp"
      alt="claudia"
      title="Home"
      onClick={goHome}
    />
  );

  const modelChooser = (
    <select
      className="model-select"
      value={model}
      onChange={(e) => changeModel(e.target.value)}
      title="Model"
    >
      {MODELS.every((m) => m.id !== model) ? <option value={model}>{model}</option> : null}
      {MODELS.map((m) => (
        <option key={m.id} value={m.id}>
          {m.label}
        </option>
      ))}
    </select>
  );

  const autoScrollBtn = (
    <button
      className={`icon-btn ${autoScroll ? "is-active" : ""}`}
      onClick={() => setAutoScroll((v) => !v)}
      title={autoScroll ? "Auto-scroll on" : "Auto-scroll off"}
      aria-pressed={autoScroll}
    >
      <FontAwesomeIcon icon={faAnglesDown} />
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
  const ctxChip = ctx ? (
    <button
      className={`ctx-chip ${ctxHot ? "hot" : ctxWarm ? "warm" : ""}`}
      onClick={compactNow}
      disabled={streaming || !sessionId}
      title={`Context: ${ctx.tokens.toLocaleString()} / ${ctx.window.toLocaleString()} tokens (${ctxPct}%)${
        ctxWarm ? " — compact recommended" : ""
      }. Click to /compact.`}
    >
      <span className="ctx-track">
        <span className="ctx-fill" style={{ width: `${Math.max(4, ctx.pct)}%` }} />
      </span>
      <span className="ctx-pct">{ctxPct}%</span>
      {ctxWarm ? <FontAwesomeIcon icon={faCompress} /> : null}
    </button>
  ) : null;

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
                      title: a.title,
                      modified: a.lastActiveAt,
                    }}
                    active
                    doing={liveIds.has(a.sessionId)}
                    folderLabel={shortName(a.folder)}
                    onOpen={() => openSession(a.folder, a.sessionId)}
                    onToggle={() => void markDone(a.sessionId)}
                    onRemove={() => void removeSession(a.folder, a.sessionId)}
                  />
                ))}
              </div>
            )}
            {folders.length === 0 ? (
              <div className="muted center pad">
                No folders yet. Add one above to see its Claude sessions.
              </div>
            ) : (
              folders.map((f) => (
                <div key={f} className="row" onClick={() => openFolder(f)}>
                  <span className="dir-icon">
                    <FontAwesomeIcon icon={faFolder} />
                  </span>
                  <div className="row-main">
                    <div className="row-title">{shortName(f)}</div>
                    <div className="row-sub mono">{f}</div>
                  </div>
                  <button
                    className="icon-btn"
                    title="Remove folder"
                    onClick={(e) => {
                      e.stopPropagation();
                      void onRemoveFolder(f);
                    }}
                  >
                    <FontAwesomeIcon icon={faXmark} />
                  </button>
                </div>
              ))
            )}
          </div>
          {pickerOpen && (
            <FolderPicker onAdd={onAddFolder} onClose={() => setPickerOpen(false)} />
          )}
        </div>
      )}

      {view === "sessions" && folder && (
        <div className="pane">
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
            {usageBtn}
          </div>
          <div className="scroll">
            {loading ? (
              <div className="muted center pad">Loading…</div>
            ) : sessions.length === 0 ? (
              <div className="muted center pad">No sessions in this folder yet.</div>
            ) : (
              sessions.map((s) => {
                const isActive = !!active[s.sessionId];
                return (
                  <SessionRow
                    key={s.sessionId}
                    s={s}
                    active={isActive}
                    doing={liveIds.has(s.sessionId)}
                    onOpen={() => openSession(folder, s.sessionId)}
                    onToggle={() =>
                      isActive
                        ? void markDone(s.sessionId)
                        : void activate(s.sessionId, folder, s.title)
                    }
                    onRemove={() => void removeSession(folder, s.sessionId)}
                  />
                );
              })
            )}
          </div>
        </div>
      )}

      {view === "chat" && folder && (
        <div className="pane">
          <div className="toolbar">
            {homeLogo}
            <button
              className="icon-btn"
              onClick={() => {
                detach();
                void openFolder(folder);
              }}
            >
              <FontAwesomeIcon icon={faArrowLeft} />
            </button>
            <div className="title mono ellipsis">
              {shortName(folder)}
              {sessionId ? ` · ${sessionId.slice(0, 8)}` : " · new"}
            </div>
            {tasks.length ? <TaskChip tasks={tasks} /> : null}
            <div className="spacer" />
            {ctxChip}
            {modelChooser}
            {streaming && (
              <button className="icon-btn is-danger" onClick={stopStream} title="Stop (Esc)">
                <FontAwesomeIcon icon={faCircleStop} />
              </button>
            )}
            {autoScrollBtn}
            {usageBtn}
          </div>

          <div className="chat-scroll">
            {loading ? (
              <div className="muted center pad">Loading transcript…</div>
            ) : (
              <StreamRenderer
                items={items}
                streaming={streaming}
                autoScroll={autoScroll}
                queue={queue}
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
