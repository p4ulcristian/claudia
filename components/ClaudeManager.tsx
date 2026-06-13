"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type {
  ChatStreamMessage,
  ClaudeEvent,
  FolderPath,
  SessionSummary,
} from "@/lib/types";
import type { UsageData } from "@/lib/usage";
import {
  addFolder as apiAddFolder,
  getFolders,
  getSessions,
  getUsage,
  loadSession,
  removeFolder as apiRemoveFolder,
} from "./api";
import { startChat, stopChat, subscribeChat } from "./stream-chat";
import FolderPicker from "./FolderPicker";
import StreamRenderer from "./StreamRenderer";
import UsagePanel from "./UsagePanel";
import {
  FontAwesomeIcon,
  faArrowLeft,
  faChartColumn,
  faFolder,
  faFolderPlus,
  faPlus,
  faXmark,
} from "./icons";

type View = "folders" | "sessions" | "chat";

function shortName(p: string): string {
  const clean = p.replace(/\/+$/, "");
  if (!clean) return "/";
  return clean.split("/").pop() || clean;
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

  const [usageOpen, setUsageOpen] = useState(false);
  const [usage, setUsage] = useState<UsageData | null>(null);
  const [usageLoading, setUsageLoading] = useState(false);
  const [usageError, setUsageError] = useState<string | null>(null);

  const [folder, setFolder] = useState<string | null>(null);
  const [sessions, setSessions] = useState<SessionSummary[]>([]);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [events, setEvents] = useState<ClaudeEvent[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [input, setInput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const abortRef = useRef<AbortController | null>(null);
  const jobIdRef = useRef<string | null>(null);
  // Skip the first URL-sync run so it doesn't wipe the query string before the
  // restore-from-URL effect has read it.
  const skipUrlSync = useRef(true);

  const refreshFolders = useCallback(async () => {
    setFolders(await getFolders());
  }, []);

  // On load, restore the view from the URL so a refresh lands on the same convo.
  useEffect(() => {
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
        setLoading(true);
        getSessions(f)
          .then(setSessions)
          .finally(() => setLoading(false));
      }
    }

    return () => abortRef.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
      setLoading(true);
      setError(null);
      const ac = new AbortController();
      abortRef.current = ac;
      try {
        const running = await subscribeChat(id, handleMsg, ac.signal);
        if (running) {
          setStreaming(true); // snapshot fills events and settles state
        } else {
          abortRef.current = null;
          setEvents(await loadSession(f, id));
          setLoading(false);
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
    setSessions([]);
    setLoading(true);
    setView("sessions");
    try {
      setSessions(await getSessions(f));
    } finally {
      setLoading(false);
    }
  };

  // ---- sessions ----
  const openSession = async (f: string, id: string) => {
    setFolder(f);
    setSessionId(id);
    setEvents([]);
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

  // Explicitly kill the running job (Stop button), then detach.
  const stopStream = useCallback(() => {
    const jobId = jobIdRef.current;
    if (jobId) void stopChat({ jobId });
    else if (sessionId) void stopChat({ session: sessionId });
    detach();
  }, [detach, sessionId]);

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
      await startChat({ folder, sessionId, prompt, signal: ac.signal }, handleMsg);
    } finally {
      if (abortRef.current === ac) abortRef.current = null;
      setStreaming(false);
    }
  };

  const sendPrompt = () => {
    const t = input.trim();
    if (!t) return;
    setInput("");
    void sendText(t);
  };

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
                setView("sessions");
              }}
            >
              <FontAwesomeIcon icon={faArrowLeft} />
            </button>
            <div className="title mono ellipsis">
              {shortName(folder)}
              {sessionId ? ` · ${sessionId.slice(0, 8)}` : " · new"}
            </div>
            <div className="spacer" />
            {streaming && (
              <button className="btn danger" onClick={stopStream}>
                Stop
              </button>
            )}
            {usageBtn}
          </div>

          <div className="chat-scroll">
            {loading ? (
              <div className="muted center pad">Loading transcript…</div>
            ) : (
              <StreamRenderer
                events={events}
                streaming={streaming}
                onAnswer={(t) => void sendText(t)}
              />
            )}
          </div>

          {error && <div className="error mono">error: {error}</div>}

          <div className="composer">
            <textarea
              value={input}
              placeholder={
                sessionId ? "Reply to resume this session…" : "Start a new session…"
              }
              rows={1}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  void sendPrompt();
                }
              }}
            />
            <button
              className="btn accent"
              disabled={streaming || !input.trim()}
              onClick={() => void sendPrompt()}
            >
              {streaming ? "…" : "Send"}
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
    </div>
  );
}
