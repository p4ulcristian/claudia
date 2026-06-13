"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  ChatStreamMessage,
  ClaudeEvent,
  FolderPath,
  SessionSummary,
} from "@/lib/types";
import type { UsageData } from "@/lib/usage";
import { contextOf, COMPACT_SUGGEST_PCT, COMPACT_AUTO_PCT } from "@/lib/context";
import {
  addFolder as apiAddFolder,
  getFolders,
  getSessions,
  getUsage,
  loadSessionDelta,
  readSessionListCache,
  removeFolder as apiRemoveFolder,
  writeSessionListCache,
} from "./api";
import {
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

export default function ClaudeManager() {
  const [view, setView] = useState<View>("folders");
  const [folders, setFolders] = useState<FolderPath[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [gitOpen, setGitOpen] = useState(false);

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

  const refreshFolders = useCallback(async () => {
    setFolders(await getFolders());
  }, []);

  // On load, restore the view from the URL so a refresh lands on the same convo.
  useEffect(() => {
    warmTranscriptCache();
    void refreshFolders();

    const sp = new URLSearchParams(window.location.search);
    const f = sp.get("folder");
    const sess = sp.get("session");
    if (f) {
      setFolder(f);
      if (sess === "new") {
        setSessionId(null);
        setView("chat");
      } else if (sess) {
        setSessionId(sess);
        setView("chat");
        void attachOrLoad(f, sess);
      } else {
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
    }

    return () => abortRef.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-focus the composer whenever a chat opens (new or existing session).
  useEffect(() => {
    if (view === "chat") inputRef.current?.focus();
  }, [view, sessionId]);

  // Reflect what's open in the tab title.
  useEffect(() => {
    let title = "claudia";
    if (view === "chat") {
      const name = sessionId
        ? (titleFromEvents(events) ?? "Session")
        : "New session";
      title = `${name} · claudia`;
    } else if (view === "sessions" && folder) {
      title = `${shortName(folder)} · claudia`;
    }
    document.title = title;
  }, [view, folder, sessionId, events]);

  // Mirror navigation state into the URL (replace, so back still leaves the app).
  useEffect(() => {
    if (skipUrlSync.current) {
      skipUrlSync.current = false;
      return;
    }
    const sp = new URLSearchParams();
    if (folder && (view === "sessions" || view === "chat")) sp.set("folder", folder);
    if (view === "chat") sp.set("session", sessionId ?? "new");
    const qs = sp.toString();
    window.history.replaceState(null, "", qs ? `/?${qs}` : "/");
  }, [view, folder, sessionId]);

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
              sessions.map((s) => (
                <div
                  key={s.sessionId}
                  className="row"
                  onClick={() => openSession(folder, s.sessionId)}
                >
                  <div className="row-main">
                    <div className="row-title ellipsis">{s.title}</div>
                    <div className="row-sub mono">
                      {fmtAgo(s.modified)} · {s.sessionId.slice(0, 8)}
                    </div>
                  </div>
                </div>
              ))
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

      {(view === "sessions" || view === "chat") && folder && (
        <button
          className={"git-fab" + (gitOpen ? " is-active" : "")}
          onClick={() => setGitOpen((v) => !v)}
          title="Git"
        >
          <FontAwesomeIcon icon={faCodeBranch} /> Git
        </button>
      )}

      {gitOpen && folder && (
        <GitPanel folder={folder} onClose={() => setGitOpen(false)} />
      )}
    </div>
  );
}
