"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
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
  getVersion,
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
  peekCachedTranscript,
  putCachedTranscript,
  warmTranscriptCache,
} from "./transcriptCache";
import type { CachedTranscript } from "./transcriptCache";
import { startChat, stopChat, subscribeChat } from "./stream-chat";
import { foldEvents, type DisplayItem } from "./fold";
import FolderPicker from "./FolderPicker";
import NewSessionPicker from "./NewSessionPicker";
import GitPanel from "./GitPanel";
import StreamRenderer from "./StreamRenderer";
import TaskChip from "./TaskChip";
import UsagePanel from "./UsagePanel";
import {
  FontAwesomeIcon,
  faChartColumn,
  faChevronDown,
  faChevronRight,
  faCircleStop,
  faCompress,
  faFolder,
  faFolderPlus,
  faPlus,
  faXmark,
  faCodeBranch,
  faPaperPlane,
  faLayerGroup,
  faCircle,
  faCircleCheck,
  faClock,
  faPencil,
  faMicrochip,
  faGaugeHigh,
  faCheck,
  faVolumeHigh,
  faVolumeXmark,
} from "./icons";

// Two states now: "home" (no conversation open — main shows the welcome) and
// "chat" (a session, new or existing, open in the main pane). Folder/session
// browsing lives in the always-present sidebar, not in separate screens.
type View = "home" | "chat";

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

// A compact session row for the sidebar. State icon on the left (running / your
// turn / done), full title that wraps so nothing is truncated, optional sub line
// (folder name or timestamp). Trailing action is either "mark done" (in-focus
// list) or "delete" (folder list); rename is offered on hover.
function SbRow({
  title,
  sub,
  state,
  colorClass,
  selected,
  href,
  onOpen,
  onPrefetch,
  trailing,
  onTrailing,
  onToggleState,
  onRename,
}: {
  title: string;
  sub?: string;
  state: "doing" | "waiting" | "done";
  colorClass?: string;
  selected?: boolean;
  href: string;
  onOpen: () => void;
  onPrefetch?: () => void;
  trailing: "done" | "delete";
  onTrailing: () => void;
  onToggleState?: () => void;
  onRename?: (title: string) => void;
}) {
  const stateIcon =
    state === "doing" ? faCircle : state === "waiting" ? faClock : faCircleCheck;
  const stateTitle = onToggleState
    ? state === "done"
      ? "Done — click to bring to focus"
      : "In focus — click to mark done"
    : state === "doing"
      ? "Running"
      : state === "waiting"
        ? "Waiting — your turn"
        : "Done";

  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(title);
  const startEdit = () => {
    setDraft(title);
    setEditing(true);
  };
  const commit = () => {
    setEditing(false);
    if (onRename && draft.trim() !== title) onRename(draft.trim());
  };

  return (
    <a
      href={editing ? undefined : href}
      className={`sb-row sb-${state}${selected ? " sb-selected" : ""}${
        colorClass ? ` ${colorClass}` : ""
      }`}
      onPointerEnter={onPrefetch}
      onClick={(e) => {
        if (editing || isModifiedClick(e)) return; // let the browser open it
        e.preventDefault();
        onOpen();
      }}
    >
      {onToggleState ? (
        <button
          className="icon-btn sb-state sb-state-btn"
          title={stateTitle}
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            onToggleState();
          }}
        >
          <FontAwesomeIcon icon={stateIcon} />
        </button>
      ) : (
        <span className="sb-state" title={stateTitle}>
          <FontAwesomeIcon icon={stateIcon} />
        </span>
      )}
      <span className="sb-main">
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
          <>
            <span className="sb-title">{title}</span>
            {sub ? <span className="sb-sub mono">{sub}</span> : null}
          </>
        )}
      </span>
      {onRename && !editing ? (
        <button
          className="icon-btn sb-act sb-edit"
          title="Rename session"
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            startEdit();
          }}
        >
          <FontAwesomeIcon icon={faPencil} />
        </button>
      ) : null}
      <button
        className={`icon-btn sb-act ${trailing === "delete" ? "sb-del" : "sb-done"}`}
        title={trailing === "delete" ? "Delete session" : "Mark done (remove from focus)"}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          onTrailing();
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

// The left navigation rail. Two regions, both fed by data the manager already
// computes: "In focus" (the in-focus sessions, formerly the tab strip) and
// "Folders" (each folder expands inline to its full session list, replacing the
// old separate folders/sessions screens). Footer carries the global toggles.
function Sidebar({
  badgeCount,
  onToggle,
  onNewSession,
  folders,
  folderMeta,
  expanded,
  sessionsByFolder,
  activeList,
  liveIds,
  titles,
  currentSessionId,
  tintClass,
  onOpenSession,
  onPrefetch,
  onMarkDone,
  onToggleActive,
  onRemoveSession,
  onRenameSession,
  onToggleFolder,
  onNewInFolder,
  onAddFolder,
  onRemoveFolder,
  colorPickerFor,
  setColorPickerFor,
  onSetFolderColor,
  soundBtn,
  usageBtn,
}: {
  badgeCount: number;
  onToggle: () => void;
  onNewSession: () => void;
  folders: FolderPath[];
  folderMeta: FolderMetaMap;
  expanded: Set<string>;
  sessionsByFolder: Record<string, SessionSummary[] | undefined>;
  activeList: { sessionId: string; folder: string; title: string; lastActiveAt: number }[];
  liveIds: Set<string>;
  titles: TitleMap;
  currentSessionId: string | null;
  tintClass: (f: string | null | undefined) => string;
  onOpenSession: (folder: string, id: string) => void;
  onPrefetch: (folder: string, id: string) => void;
  onMarkDone: (id: string) => void;
  onToggleActive: (id: string, folder: string, title: string) => void;
  onRemoveSession: (folder: string, id: string) => void;
  onRenameSession: (id: string, title: string) => void;
  onToggleFolder: (folder: string) => void;
  onNewInFolder: (folder: string) => void;
  onAddFolder: () => void;
  onRemoveFolder: (folder: string) => void;
  colorPickerFor: string | null;
  setColorPickerFor: (f: string | null) => void;
  onSetFolderColor: (f: string, color: string | null) => void;
  soundBtn: ReactNode;
  usageBtn: ReactNode;
}) {
  const activeIds = new Set(activeList.map((a) => a.sessionId));

  return (
    <aside className="sidebar">
      <div className="sb-head">
        <span className="brand-logo-click" onClick={onToggle} title="Toggle sidebar">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="brand-logo" src="/claudia.webp" alt="claudia" />
          {badgeCount > 0 ? <span className="brand-badge">{badgeCount}</span> : null}
        </span>
        <div className="spacer" />
        <button className="icon-btn" onClick={onToggle} title="Collapse sidebar">
          <FontAwesomeIcon icon={faChevronRight} flip="horizontal" />
        </button>
      </div>

      <button className="btn accent sb-new" onClick={onNewSession}>
        <FontAwesomeIcon icon={faPlus} /> New session
      </button>

      <div className="sb-scroll">
        {activeList.length > 0 && (
          <div className="sb-section">
            <div className="sb-section-head">
              <span>In focus</span>
              <span className="sb-count">{activeList.length}</span>
            </div>
            {activeList.map((a) => (
              <SbRow
                key={a.sessionId}
                title={titles[a.sessionId] ?? a.title}
                sub={shortName(a.folder)}
                state={liveIds.has(a.sessionId) ? "doing" : "waiting"}
                colorClass={tintClass(a.folder)}
                selected={a.sessionId === currentSessionId}
                href={hrefFor(a.folder, a.sessionId)}
                onOpen={() => onOpenSession(a.folder, a.sessionId)}
                onPrefetch={() => onPrefetch(a.folder, a.sessionId)}
                trailing="done"
                onTrailing={() => onMarkDone(a.sessionId)}
                onRename={(t) => onRenameSession(a.sessionId, t)}
              />
            ))}
          </div>
        )}

        <div className="sb-section">
          <div className="sb-section-head">
            <span>Folders</span>
            <button className="icon-btn sb-add" title="Add folder" onClick={onAddFolder}>
              <FontAwesomeIcon icon={faFolderPlus} />
            </button>
          </div>
          {folders.length === 0 ? (
            <div className="sb-empty">No folders yet.</div>
          ) : (
            folders.map((f) => {
              const isExp = expanded.has(f);
              const list = sessionsByFolder[f];
              const folderLive = activeList.some(
                (a) => a.folder === f && liveIds.has(a.sessionId),
              );
              return (
                <div className="sb-folder" key={f}>
                  <div
                    className={`sb-folder-row${tintClass(f) ? ` ${tintClass(f)}` : ""}`}
                    onClick={() => onToggleFolder(f)}
                    title={f}
                  >
                    <span className="sb-chev">
                      <FontAwesomeIcon icon={isExp ? faChevronDown : faChevronRight} />
                    </span>
                    <span className="sb-folder-ic">
                      <FontAwesomeIcon icon={faFolder} />
                    </span>
                    <span className="sb-folder-name">{shortName(f)}</span>
                    {folderLive && <span className="sb-folder-live" aria-hidden />}
                    <div
                      className="folder-color-pick"
                      onClick={(e) => {
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
                                  onSetFolderColor(f, c);
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
                                onSetFolderColor(f, null);
                                setColorPickerFor(null);
                              }}
                            />
                          </div>
                        </>
                      )}
                    </div>
                    <button
                      className="icon-btn sb-folder-act"
                      title="New session here"
                      aria-label="New session"
                      onClick={(e) => {
                        e.stopPropagation();
                        onNewInFolder(f);
                      }}
                    >
                      <FontAwesomeIcon icon={faPlus} />
                    </button>
                    <button
                      className="icon-btn sb-folder-act sb-del"
                      title="Remove folder"
                      onClick={(e) => {
                        e.stopPropagation();
                        onRemoveFolder(f);
                      }}
                    >
                      <FontAwesomeIcon icon={faXmark} />
                    </button>
                  </div>
                  {isExp && (
                    <div className="sb-folder-sessions">
                      {list === undefined ? (
                        <div className="sb-loading">Loading…</div>
                      ) : list.length === 0 ? (
                        <div className="sb-empty">No sessions yet.</div>
                      ) : (
                        list.map((s) => {
                          const live = liveIds.has(s.sessionId);
                          const state = live
                            ? "doing"
                            : activeIds.has(s.sessionId)
                              ? "waiting"
                              : "done";
                          const title = titles[s.sessionId] ?? s.title;
                          return (
                            <SbRow
                              key={s.sessionId}
                              title={title}
                              sub={
                                state === "done"
                                  ? fmtAgo(s.modified)
                                  : state === "doing"
                                    ? "running"
                                    : "waiting"
                              }
                              state={state}
                              colorClass={tintClass(f)}
                              selected={s.sessionId === currentSessionId}
                              href={hrefFor(f, s.sessionId)}
                              onOpen={() => onOpenSession(f, s.sessionId)}
                              onPrefetch={() => onPrefetch(f, s.sessionId)}
                              trailing="delete"
                              onTrailing={() => onRemoveSession(f, s.sessionId)}
                              onToggleState={() =>
                                onToggleActive(s.sessionId, f, title)
                              }
                              onRename={(t) => onRenameSession(s.sessionId, t)}
                            />
                          );
                        })
                      )}
                    </div>
                  )}
                </div>
              );
            })
          )}
        </div>
      </div>

      <div className="sb-foot">
        {soundBtn}
        {usageBtn}
      </div>
    </aside>
  );
}

export default function ClaudeManager() {
  const [view, setView] = useState<View>("home");
  const [folders, setFolders] = useState<FolderPath[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  // Sidebar open/collapsed, persisted so it survives reloads (read in the
  // initializer so the first paint is correct, like model/muted below).
  const [sidebarOpen, setSidebarOpen] = useState(() => {
    if (typeof window === "undefined") return true;
    try {
      return localStorage.getItem("claudia-sidebar") !== "closed";
    } catch {
      return true;
    }
  });
  // Which folders are expanded in the sidebar tree, and the (lazily fetched)
  // session list per folder. Independent of `folder` (the open chat's folder).
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [sessionsByFolder, setSessionsByFolder] = useState<
    Record<string, SessionSummary[] | undefined>
  >({});
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
  // The tab strip's "+" opens a quick chooser over watched folders; "Browse…"
  // there escalates to the full filesystem picker (newTabPicker).
  const [newSessionPicker, setNewSessionPicker] = useState(false);
  const [newTabPicker, setNewTabPicker] = useState(false);

  const [folder, setFolder] = useState<string | null>(null);

  const [sessionId, setSessionId] = useState<string | null>(null);
  const [events, setEvents] = useState<ClaudeEvent[]>([]);
  const [streaming, setStreaming] = useState(false);
  // Epoch millis the current turn began (server-sourced via snapshot, or set
  // optimistically on send). Drives the "answering for Xs" timer; null when idle.
  const [turnStartedAt, setTurnStartedAt] = useState<number | null>(null);
  const [input, setInput] = useState("");
  const [queue, setQueue] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // The thin chat header auto-hides while you scroll the transcript down and
  // slides back when you scroll up (or reach the top).
  const [headHidden, setHeadHidden] = useState(false);
  // The composer lives in a floating circle that expands into the input on
  // demand, so the transcript gets the full height while you read.
  const [composerOpen, setComposerOpen] = useState(false);

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
  // The floating composer popover + its circle, for click-away detection.
  const composerPopRef = useRef<HTMLDivElement | null>(null);
  const fabRef = useRef<HTMLButtonElement | null>(null);
  // Hover-intent timer so a quick mouse pass over the circle doesn't open it.
  const fabHoverTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // The chat scroll viewport, watched to drive the header auto-hide.
  const chatScrollRef = useRef<HTMLDivElement | null>(null);
  const lastChatTop = useRef(0);
  // Idle timer: hides the header a few seconds after the last interaction.
  const headHideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Finish-sound plumbing. prevLiveRef holds last tick's running set so the poll
  // can spot sessions that just stopped; seededLiveRef skips the very first tick
  // (so a refresh mid-run doesn't false-fire). The audio element is created
  // lazily. view/session/muted are mirrored into refs because the poll effect
  // runs once (empty deps) and would otherwise read stale values.
  const prevLiveRef = useRef<Map<string, string>>(new Map());
  const seededLiveRef = useRef(false);
  // Server boot id from the last poll. The first tick records it; a later tick
  // seeing a different id means the service restarted under us, so the tab is
  // running stale JS against a rebuilt server — reload to pick up fresh chunks.
  const bootIdRef = useRef<string | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const viewRef = useRef(view);
  const sessionIdRef = useRef(sessionId);
  const mutedRef = useRef(muted);
  viewRef.current = view;
  sessionIdRef.current = sessionId;
  mutedRef.current = muted;
  // Current chat's display name, mirrored for the finish notification (applyMsg
  // runs from a stable callback and would otherwise read a stale title).
  const chatTitleRef = useRef("");
  chatTitleRef.current = sessionId
    ? (titles[sessionId] ?? titleFromEvents(events) ?? "Session")
    : "New session";
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
    view: "home",
    folder: null,
  });

  const refreshFolders = useCallback(async () => {
    setFolders(await getFolders());
  }, []);

  // Persisted sidebar toggle.
  const toggleSidebar = useCallback(() => {
    setSidebarOpen((v) => {
      const next = !v;
      try {
        localStorage.setItem("claudia-sidebar", next ? "open" : "closed");
      } catch {
        /* ignore */
      }
      return next;
    });
  }, []);

  // Mirror of sessionsByFolder so the stable callbacks below can check "is this
  // folder already loaded?" without taking the map as a dependency (which would
  // churn their identity on every list update).
  const sessionsByFolderRef = useRef(sessionsByFolder);
  sessionsByFolderRef.current = sessionsByFolder;

  // Fetch a folder's session list into the tree: paint the cache instantly, then
  // revalidate over the network. Skips the commit when nothing changed.
  const loadFolderSessions = useCallback(async (f: string) => {
    const cached = readSessionListCache(f);
    if (cached) setSessionsByFolder((m) => ({ ...m, [f]: cached }));
    try {
      const fresh = await getSessions(f);
      setSessionsByFolder((m) => {
        const prev = m[f];
        if (prev && sameSessions(prev, fresh)) return m;
        return { ...m, [f]: fresh };
      });
      writeSessionListCache(f, fresh);
    } catch {
      /* keep whatever cache we painted */
    }
  }, []);

  // Expand a folder in the tree (and load it if we haven't yet). Idempotent.
  const expandFolder = useCallback(
    (f: string) => {
      setExpanded((s) => (s.has(f) ? s : new Set(s).add(f)));
      if (!sessionsByFolderRef.current[f]) void loadFolderSessions(f);
    },
    [loadFolderSessions],
  );

  // Sidebar folder header click: toggle expansion, lazily loading on first open.
  const toggleFolder = useCallback(
    (f: string) => {
      setExpanded((s) => {
        const next = new Set(s);
        if (next.has(f)) {
          next.delete(f);
        } else {
          next.add(f);
          if (!sessionsByFolderRef.current[f]) void loadFolderSessions(f);
        }
        return next;
      });
    },
    [loadFolderSessions],
  );

  // Focus the input the moment the composer opens, so you can type instantly.
  useEffect(() => {
    if (composerOpen) inputRef.current?.focus();
  }, [composerOpen]);

  // Click anywhere outside the composer popover (or its circle) collapses it.
  // Uses pointerdown on the document so it doesn't block transcript scrolling.
  useEffect(() => {
    if (!composerOpen) return;
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node;
      if (composerPopRef.current?.contains(t) || fabRef.current?.contains(t)) return;
      setComposerOpen(false);
    };
    document.addEventListener("pointerdown", onDown);
    return () => document.removeEventListener("pointerdown", onDown);
  }, [composerOpen]);

  // Surface errors by popping the composer open (that's where they render).
  useEffect(() => {
    if (error) setComposerOpen(true);
  }, [error]);

  // Arm the idle timer that hides the header after a short pause.
  const HEAD_IDLE_MS = 1500;
  const armHeadHide = useCallback(() => {
    if (headHideTimer.current) clearTimeout(headHideTimer.current);
    headHideTimer.current = setTimeout(() => setHeadHidden(true), HEAD_IDLE_MS);
  }, []);
  // Reveal, then re-arm the idle hide (scroll / open).
  const revealHead = useCallback(() => {
    setHeadHidden(false);
    armHeadHide();
  }, [armHeadHide]);
  // Reveal and keep shown — no idle hide while the pointer is over the top edge
  // or the header itself.
  const holdHead = useCallback(() => {
    setHeadHidden(false);
    if (headHideTimer.current) clearTimeout(headHideTimer.current);
  }, []);

  // Auto-hide the chat header: hide on downward scroll or after an idle period;
  // reveal on upward scroll, near the top, or when a chat opens. Re-attaches per
  // chat (the viewport remounts on view swap).
  useEffect(() => {
    revealHead();
    const el = chatScrollRef.current;
    if (!el) {
      return () => {
        if (headHideTimer.current) clearTimeout(headHideTimer.current);
      };
    }
    lastChatTop.current = el.scrollTop;
    const onScroll = () => {
      const top = el.scrollTop;
      const prev = lastChatTop.current;
      lastChatTop.current = top;
      if (top < 24) revealHead();
      else if (top > prev + 6) {
        if (headHideTimer.current) clearTimeout(headHideTimer.current);
        setHeadHidden(true);
      } else if (top < prev - 6) revealHead();
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      el.removeEventListener("scroll", onScroll);
      if (headHideTimer.current) clearTimeout(headHideTimer.current);
    };
  }, [view, sessionId, loading, revealHead]);

  // Reflect what's open in the tab title.
  useEffect(() => {
    let title = "claudia";
    if (view === "chat" && folder) {
      const name = sessionId
        ? (titleFromEvents(events) ?? "Session")
        : "New session";
      title = `${shortName(folder)} — ${name}`;
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
    if (view === "chat" && folder) {
      sp.set("folder", folder);
      sp.set("session", sessionId ?? "new");
    }
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

  // Ask for OS-notification permission. Browsers reject requestPermission()
  // outside a user gesture and prompting on load is hostile, so callers only
  // invoke this from a real interaction (first click/keypress, or unmuting).
  // No-op once the user has already allowed or denied.
  const ensureNotifyPermission = useCallback(() => {
    try {
      if (
        typeof Notification !== "undefined" &&
        Notification.permission === "default"
      ) {
        void Notification.requestPermission().catch(() => {});
      }
    } catch {
      /* notifications unavailable */
    }
  }, []);

  // Pop an OS banner that a turn finished — but only when the tab is hidden. If
  // it's on screen the chime and the live UI already tell you, so a banner would
  // just be noise. Gated on the same mute toggle as the sound. Clicking it
  // brings the window forward.
  const showFinishNotification = useCallback((body: string) => {
    try {
      if (mutedRef.current) return;
      if (typeof Notification === "undefined") return;
      if (Notification.permission !== "granted") return;
      if (document.visibilityState === "visible") return;
      const n = new Notification("claudia — finished", {
        body,
        tag: "claudia-finish", // collapse multiple finishes into one banner
      });
      n.onclick = () => {
        window.focus();
        n.close();
      };
    } catch {
      /* notifications unavailable */
    }
  }, []);

  // Request notification permission on the first user gesture, covering both
  // people who start a turn here and people who only watch. Asks once; unmuting
  // later (toggleMuted) re-asks if it was skipped because the chime was muted.
  useEffect(() => {
    const ask = () => {
      if (!mutedRef.current) ensureNotifyPermission();
      window.removeEventListener("pointerdown", ask);
      window.removeEventListener("keydown", ask);
    };
    window.addEventListener("pointerdown", ask);
    window.addEventListener("keydown", ask);
    return () => {
      window.removeEventListener("pointerdown", ask);
      window.removeEventListener("keydown", ask);
    };
  }, [ensureNotifyPermission]);

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
            // Sessions present last tick but gone now just finished. Skip the
            // one you're watching focused — its finish is announced precisely by
            // the SSE "done" frame, so handling it here too would double up.
            const finished = [...prevLiveRef.current.keys()].filter(
              (id) => !nextIds.has(id),
            );
            const background = finished.filter(
              (id) =>
                !(viewRef.current === "chat" && sessionIdRef.current === id),
            );
            if (background.length && !mutedRef.current) playFinishSound();
            if (background.length === 1) {
              showFinishNotification(
                prevLiveRef.current.get(background[0]) ?? "A session finished",
              );
            } else if (background.length > 1) {
              showFinishNotification(`${background.length} sessions finished`);
            }
          }
          prevLiveRef.current = new Map(l.map((p) => [p.sessionId, p.title]));
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
      // Reload if the server restarted: the first reachable tick records the
      // boot id, a later differing id means we're a stale tab on a rebuilt
      // server. Failed fetches (the rebuild's downtime) just skip — we only act
      // on a successful response carrying a new id.
      getVersion()
        .then((boot) => {
          if (!alive) return;
          if (bootIdRef.current === null) bootIdRef.current = boot;
          else if (bootIdRef.current !== boot) window.location.reload();
        })
        .catch(() => {});
    };
    tick();
    const id = setInterval(tick, 4000);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [playFinishSound, showFinishNotification]);

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
  // the task list out of it. Reconcile against the previous fold: an item whose
  // key+sig is unchanged keeps its exact object identity, so the memoized row
  // skips re-rendering even when the events array is swapped wholesale (a reset
  // or live snapshot). This is what turns a "reload" into an invisible merge.
  const prevItemsRef = useRef<{ sid: string | null; items: DisplayItem[] }>({
    sid: null,
    items: [],
  });
  const items = useMemo(() => {
    const next = foldEvents(events);
    // Only reuse objects from the SAME session — keys are per-session ordinals,
    // so reusing across a session switch could bind a row to the wrong content.
    const byKey = new Map<string, DisplayItem>();
    if (prevItemsRef.current.sid === sessionId) {
      for (const it of prevItemsRef.current.items) if (it.key) byKey.set(it.key, it);
    }
    const reconciled = next.map((it) => {
      const prev = it.key ? byKey.get(it.key) : undefined;
      return prev && prev.sig === it.sig ? prev : it;
    });
    prevItemsRef.current = { sid: sessionId, items: reconciled };
    return reconciled;
  }, [events, sessionId]);
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

  // Warm the hot in-memory cache from IDB for every listed session, so opening
  // any of them is a synchronous Stage-0 hit (no skeleton). IDB-only — cheap, no
  // network; the network top-up happens on hover (prefetchSession) or open.
  useEffect(() => {
    const listed = Object.values(sessionsByFolder)
      .flat()
      .filter((s): s is SessionSummary => !!s);
    const ids = [
      ...activeList.map((a) => a.sessionId),
      ...listed.map((s) => s.sessionId),
    ];
    for (const id of ids.slice(0, 40)) {
      if (!peekCachedTranscript(id)) void getCachedTranscript(id);
    }
  }, [activeList, sessionsByFolder]);

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
        setTurnStartedAt(msg.status === "running" ? msg.startedAt : null);
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
        setTurnStartedAt(null);
        // Chime for the session you're watching. The live poll deliberately
        // skips the focused session (see below), so this is its only sound; it
        // fires the moment the turn ends rather than up to a poll-tick later.
        if (!mutedRef.current) playFinishSound();
        // And a banner if you've tabbed away from it.
        showFinishNotification(chatTitleRef.current || "Your turn is ready");
        break;
    }
  }, [playFinishSound, showFinishNotification]);

  // Reconnect to a running job for this session, else load its transcript.
  const attachOrLoad = useCallback(
    async (f: string, id: string, seed?: CachedTranscript | null) => {
      // Take ownership of the chat view: abort any prior stream and claim a
      // fresh generation. Every state write below is gated on it still being
      // current, so a rapid open A → open B can't paint A's transcript into B.
      invalidateStream();
      const gen = streamGenRef.current;
      setError(null);
      // The caller may pass a synchronous seed (peekCachedTranscript) it already
      // painted — in that case skip the async IDB read and re-paint entirely.
      // Otherwise read IDB and paint from it before any network.
      const cached = seed ?? (await getCachedTranscript(id));
      if (gen !== streamGenRef.current) return;
      if (!seed) {
        if (cached) {
          setEvents(cached.events);
          setLoading(false);
        } else {
          setLoading(true);
        }
      }
      const ac = new AbortController();
      abortRef.current = ac;
      try {
        // Fire the delta fetch concurrently with the live-check instead of
        // waiting to learn it's not live first — for the common (not-live) case
        // this removes a whole round-trip from the time-to-settle. If the session
        // turns out to be live, the snapshot wins and this result is discarded.
        const deltaPromise = loadSessionDelta(
          f,
          id,
          cached?.size ?? 0,
          cached?.modified ?? 0,
        ).catch(() => null);
        const running = await subscribeChat(id, (m) => applyMsg(gen, m), ac.signal);
        if (gen !== streamGenRef.current) return;
        if (running) {
          setStreaming(true); // snapshot replaces events with live state
        } else {
          // No live job for this session: it's just a saved transcript. Clear
          // any streaming flag inherited from the chat we navigated away from,
          // otherwise the new chat shows phantom "..." working dots.
          setStreaming(false);
          if (abortRef.current === ac) abortRef.current = null;
          const res = await deltaPromise;
          if (gen !== streamGenRef.current) return;
          if (!res) {
            setLoading(false); // delta failed; keep whatever cache we painted
            return;
          }
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

  // Watchdog for a stuck "streaming" state. `streaming` is normally cleared by
  // the SSE "done" frame, but a stalled/half-open connection (proxy buffering,
  // a dropped socket) can leave the reader hanging with no terminal frame — so
  // the chat shows "Claude is answering" forever while nothing arrives. The 4s
  // live poll is authoritative about what's actually running: if we believe a
  // session is streaming but the server reports no running job for it, the turn
  // really ended. We give the poll a couple of cycles to catch a just-started
  // turn (it can lag the stream by up to 4s), then reconcile: drop the flag and
  // reload the saved transcript so the final answer shows.
  useEffect(() => {
    if (!streaming || !sessionId || !folder) return;
    if (liveIds.has(sessionId)) return; // server confirms it's running
    const t = setTimeout(() => {
      setStreaming(false);
      void attachOrLoad(folder, sessionId);
    }, 9000);
    return () => clearTimeout(t);
  }, [streaming, sessionId, folder, liveIds, attachOrLoad]);

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
        setView("home");
        return;
      }
      if (sess === "new") {
        setFolder(f);
        setSessionId(null);
        setEvents([]);
        setStreaming(false);
        setView("chat");
        setComposerOpen(true);
        expandFolder(f);
      } else if (sess) {
        setFolder(f);
        setSessionId(sess);
        const seed = peekCachedTranscript(sess);
        setEvents(seed ? seed.events : []);
        setLoading(!seed);
        setView("chat");
        setComposerOpen(false);
        expandFolder(f);
        void attachOrLoad(f, sess, seed);
      } else {
        // Folder-only URL: nothing open in main, just reveal the folder's
        // sessions in the sidebar tree.
        setSessionId(null);
        setFolder(null);
        setView("home");
        expandFolder(f);
      }
    },
    [attachOrLoad, invalidateStream, expandFolder],
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

  // Tab-strip "+": pick a folder (adding it to the watched set if new), then
  // open a fresh session in it. apiAddFolder is idempotent for known folders.
  const startNewSessionInFolder = async (path: string) => {
    setNewTabPicker(false);
    try {
      setFolders(await apiAddFolder(path));
    } catch {
      /* already watched / add failed — open the session anyway */
    }
    newSession(path);
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

  // Reveal a folder in the sidebar tree, opening the sidebar if collapsed. Used
  // by the chat header's project crumb to surface the open session's siblings.
  const revealFolder = useCallback(
    (f: string) => {
      setSidebarOpen(true);
      try {
        localStorage.setItem("claudia-sidebar", "open");
      } catch {
        /* ignore */
      }
      expandFolder(f);
    },
    [expandFolder],
  );

  // ---- sessions ----
  // Warm a session's transcript into the cache before it's opened, so the open
  // is a synchronous Stage-0 hit (no skeleton, no reconcile flash). Called on
  // row hover and, in bulk, for listed sessions. Cheap and idempotent: dedupes
  // in-flight fetches, and skips entirely once the session is hot in memory.
  const prefetchingRef = useRef<Set<string>>(new Set());
  const prefetchSession = useCallback((f: string, id: string) => {
    if (peekCachedTranscript(id) || prefetchingRef.current.has(id)) return;
    prefetchingRef.current.add(id);
    void (async () => {
      try {
        // Pull any prior-page-load entry from IDB into the hot mem layer first;
        // that alone already makes the next open synchronous.
        const cached = await getCachedTranscript(id);
        // Then top it up over the network so the eventual open's reconcile is a
        // no-op (the cache already holds the newest events).
        const res = await loadSessionDelta(
          f,
          id,
          cached?.size ?? 0,
          cached?.modified ?? 0,
        );
        const events =
          !cached || res.reset
            ? res.events
            : res.events.length
              ? [...cached.events, ...res.events]
              : cached.events;
        await putCachedTranscript({
          sessionId: id,
          events,
          size: res.size,
          modified: res.modified,
        });
      } catch {
        /* best-effort warming — the open path remains the source of truth */
      } finally {
        prefetchingRef.current.delete(id);
      }
    })();
  }, []);

  // On a narrow screen the sidebar is an overlay; collapse it after a pick so the
  // chat is visible. Not persisted — desktop keeps its remembered open state.
  const closeSidebarOnMobile = useCallback(() => {
    if (typeof window !== "undefined" && window.innerWidth < 760) {
      setSidebarOpen(false);
    }
  }, []);

  const openSession = async (f: string, id: string) => {
    setFolder(f);
    setSessionId(id);
    // Synchronous cache hit → paint the transcript in this same render so the
    // chat opens already at the bottom, no empty/loading flash. On a miss show
    // the skeleton (never the "no messages yet" empty state) while we fetch.
    const seed = peekCachedTranscript(id);
    setEvents(seed ? seed.events : []);
    setLoading(!seed);
    setQueue([]);
    setError(null);
    setView("chat");
    setComposerOpen(false); // existing session opens in reading mode
    expandFolder(f);
    closeSidebarOnMobile();
    await attachOrLoad(f, id, seed);
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
    setComposerOpen(true); // a fresh session opens ready to type
    expandFolder(f);
    closeSidebarOnMobile();
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
    const next = (sessionsByFolder[f] ?? []).filter((s) => s.sessionId !== id);
    setSessionsByFolder((m) => ({ ...m, [f]: next }));
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
      void loadFolderSessions(f);
    }
  };

  // ---- chat ----
  // Detach from the stream without killing the job (refresh / navigate away).
  const detach = useCallback(() => {
    invalidateStream();
    setStreaming(false);
    setTurnStartedAt(null);
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

  // Toggle (and persist) the finish chime + notification mute. Turning alerts
  // back on is a good moment to (re)request notification permission.
  const toggleMuted = () => {
    const next = !muted;
    if (!next) ensureNotifyPermission();
    setMuted(next);
    try {
      localStorage.setItem("claudia-sound-muted", String(next));
    } catch {
      /* ignore */
    }
  };

  // Esc stops the current generation, like the Stop button.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (streaming) {
        e.preventDefault();
        stopStream();
      } else if (composerOpen) {
        e.preventDefault();
        setComposerOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [streaming, stopStream, composerOpen]);

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
    setTurnStartedAt(Date.now()); // optimistic; the snapshot's startedAt reconciles

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
    // Collapse back to the circle so the reply streams into full height.
    setComposerOpen(false);
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

  // Badge counts the in-focus sessions (doing + waiting) so the logo carries a
  // running "how many sessions need me" number.
  const homeBadgeCount = activeList.length;

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
      title={muted ? "Finish alerts off — click to enable sound + notifications" : "Finish alerts on — click to mute"}
      aria-label={muted ? "Enable finish alerts" : "Mute finish alerts"}
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

  // The thin chat header: sidebar toggle + the project › title breadcrumb. The
  // toolbar's old right-hand cluster (model / ctx / tasks) has moved down into
  // the composer so the transcript gets the full height.
  const headLogo = (
    <span
      className="brand-logo-click chat-head-logo"
      onClick={toggleSidebar}
      title="Toggle sidebar"
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img className="brand-logo" src="/claudia.webp" alt="claudia" />
      {homeBadgeCount > 0 ? (
        <span className="brand-badge">{homeBadgeCount}</span>
      ) : null}
    </span>
  );
  const chatHead = (
    <div
      className={`chat-head${headHidden ? " is-hidden" : ""}`}
      onMouseEnter={holdHead}
      onMouseLeave={armHeadHide}
    >
      {headLogo}
      {view === "chat" && folder ? (
        <Breadcrumbs
          project={shortName(folder)}
          onProject={() => revealFolder(folder)}
          title={
            sessionId
              ? (titles[sessionId] ?? titleFromEvents(events) ?? "Session")
              : "New session"
          }
          onRename={
            sessionId ? (t) => void renameSession(sessionId, t) : undefined
          }
        />
      ) : null}
    </div>
  );

  return (
    <div className={`cm${sidebarOpen ? " sidebar-open" : " sidebar-closed"}`}>
      {sidebarOpen && (
        <>
          <Sidebar
            badgeCount={homeBadgeCount}
            onToggle={toggleSidebar}
            onNewSession={() => setNewSessionPicker(true)}
            folders={folders}
            folderMeta={folderMeta}
            expanded={expanded}
            sessionsByFolder={sessionsByFolder}
            activeList={activeList}
            liveIds={liveIds}
            titles={titles}
            currentSessionId={sessionId}
            tintClass={tintClass}
            onOpenSession={(f, id) => void openSession(f, id)}
            onPrefetch={(f, id) => prefetchSession(f, id)}
            onMarkDone={(id) => void markDone(id)}
            onToggleActive={(id, f, t) => void toggleActive(id, f, t)}
            onRemoveSession={(f, id) => void removeSession(f, id)}
            onRenameSession={(id, t) => void renameSession(id, t)}
            onToggleFolder={toggleFolder}
            onNewInFolder={(f) => newSession(f)}
            onAddFolder={() => setPickerOpen(true)}
            onRemoveFolder={(f) => void onRemoveFolder(f)}
            colorPickerFor={colorPickerFor}
            setColorPickerFor={setColorPickerFor}
            onSetFolderColor={(f, c) => void onSetFolderColor(f, c)}
            soundBtn={soundBtn}
            usageBtn={usageBtn}
          />
          {/* Mobile-only scrim; the sidebar floats over the chat there. */}
          <div className="sb-backdrop" onClick={toggleSidebar} />
        </>
      )}

      <div className="main">
        {view === "chat" && folder ? (
          <div
            className={`pane${tintClass(folder) ? ` ${tintClass(folder)}` : ""}`}
          >
            <div className="head-hover-zone" onMouseEnter={holdHead} />
            {chatHead}

            <div className="chat-scroll" ref={chatScrollRef}>
              {loading ? (
                <SkeletonRows count={4} />
              ) : (
                <StreamRenderer
                  items={items}
                  streaming={streaming}
                  startedAt={turnStartedAt}
                  queue={queue}
                  sessionId={sessionId}
                  onAnswer={(t) => void sendText(t)}
                  onCancelQueued={cancelQueued}
                />
              )}
            </div>

            {/* Floating composer: a circle that expands into the input. */}
            {composerOpen && (
              <div className="composer-pop" ref={composerPopRef}>
                {error && <div className="error mono">error: {error}</div>}
                <textarea
                  ref={inputRef}
                  value={input}
                  placeholder={
                    sessionId
                      ? "Reply to resume this session…"
                      : "Start a new session…"
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
                <div className="composer-row">
                  {/* Live signals live in the panel (visible while composing). */}
                  <div className="composer-tools">
                    {tasks.length ? <TaskChip tasks={tasks} /> : null}
                    {ctxChip}
                    {modelChooser}
                  </div>
                  <div className="spacer" />
                  {streaming && (
                    <button
                      className="btn danger"
                      onClick={stopStream}
                      title="Stop (Esc)"
                      aria-label="Stop"
                    >
                      <FontAwesomeIcon icon={faCircleStop} />
                    </button>
                  )}
                  <button
                    className="btn accent"
                    disabled={!input.trim()}
                    onClick={submitInput}
                    title={streaming ? "Queue" : "Send"}
                    aria-label={streaming ? "Queue" : "Send"}
                  >
                    <FontAwesomeIcon
                      icon={streaming ? faLayerGroup : faPaperPlane}
                    />
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

            <button
              ref={fabRef}
              className={`fab${streaming ? " is-streaming" : ""}${
                composerOpen ? " is-open" : ""
              }`}
              onClick={() => setComposerOpen((v) => !v)}
              onPointerEnter={(e) => {
                // Mouse only — touch uses the tap. Open after a short rest so a
                // quick pass-over doesn't trigger it.
                if (e.pointerType === "touch" || composerOpen) return;
                if (fabHoverTimer.current) clearTimeout(fabHoverTimer.current);
                fabHoverTimer.current = setTimeout(() => setComposerOpen(true), 140);
              }}
              onPointerLeave={() => {
                if (fabHoverTimer.current) clearTimeout(fabHoverTimer.current);
              }}
              title={composerOpen ? "Close composer (Esc)" : "Write a message"}
              aria-label={composerOpen ? "Close composer" : "Write a message"}
            >
              <FontAwesomeIcon icon={composerOpen ? faXmark : faPencil} />
            </button>
          </div>
        ) : (
          <div className="pane home-pane">
            <div className="head-hover-zone" onMouseEnter={holdHead} />
            {chatHead}
            <div className="home-empty">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img className="home-logo" src="/claudia.webp" alt="claudia" />
              <p>
                Pick a session from the sidebar, or start a new one.
              </p>
              <button
                className="btn accent"
                onClick={() => setNewSessionPicker(true)}
              >
                <FontAwesomeIcon icon={faPlus} /> New session
              </button>
            </div>
          </div>
        )}
      </div>

      {pickerOpen && (
        <FolderPicker onAdd={onAddFolder} onClose={() => setPickerOpen(false)} />
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

      {newSessionPicker && (
        <NewSessionPicker
          folders={folders}
          meta={folderMeta}
          onPick={(p) => {
            setNewSessionPicker(false);
            startNewSessionInFolder(p);
          }}
          onBrowse={() => {
            setNewSessionPicker(false);
            setNewTabPicker(true);
          }}
          onClose={() => setNewSessionPicker(false)}
        />
      )}

      {newTabPicker && (
        <FolderPicker
          onAdd={startNewSessionInFolder}
          onClose={() => setNewTabPicker(false)}
        />
      )}
    </div>
  );
}
