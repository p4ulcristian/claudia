// Shared types between the server lib, route handlers, and client.

/** A watched project folder (absolute path). */
export type FolderPath = string;

/** One past Claude session, summarised from its transcript file. */
export interface SessionSummary {
  sessionId: string;
  title: string;
  /** Last-modified time, epoch millis. */
  modified: number;
  /** File size in bytes. */
  size: number;
}

/** Incremental transcript load — events appended past a byte offset. */
export interface TranscriptDelta {
  events: ClaudeEvent[];
  /** New total file size in bytes — the next `since`. */
  size: number;
  /** Last-modified time, epoch millis. */
  modified: number;
  /** True => discard any cache and treat `events` as the full transcript. */
  reset: boolean;
}

// ---- git smartlog (sidebar) ----

export type GitRefType = "head" | "branch" | "remote" | "tag" | "detached";

export interface GitRef {
  type: GitRefType;
  name: string;
}

export interface GitCommit {
  hash: string;
  parents: string[];
  author: string;
  /** Relative date, e.g. "2 days ago". */
  when: string;
  refs: GitRef[];
  subject: string;
  /** Lane column assigned by the graph layout. */
  col: number;
}

export interface GitSmartlog {
  commits: GitCommit[];
  head: string;
  currentBranch: string;
  detached: boolean;
}

/** One changed file: x = staged (index) code, y = unstaged (worktree) code. */
export interface GitStatusFile {
  x: string;
  y: string;
  path: string;
}

export interface GitStatus {
  ahead: number;
  behind: number;
  files: GitStatusFile[];
}

/** Combined payload for the git drawer. `error` set when the folder isn't a repo. */
export interface GitData extends GitSmartlog {
  repo: { name: string; path: string };
  status: GitStatus;
  error?: string;
}

/** One file touched by a commit. */
export interface GitFileChange {
  /** Single-letter status: A added, M modified, D deleted, R renamed, C copied, T type-changed. */
  status: string;
  path: string;
  /** Previous path, for renames/copies. */
  oldPath?: string;
  /** Lines added; -1 for binary files. */
  additions: number;
  /** Lines deleted; -1 for binary files. */
  deletions: number;
}

/** Full detail for a single commit: metadata + the files it changed. */
export interface GitCommitDetail {
  hash: string;
  parents: string[];
  author: string;
  authorEmail: string;
  /** Relative date, e.g. "2 days ago". */
  when: string;
  /** Absolute date string. */
  date: string;
  subject: string;
  /** Commit message body (everything after the subject line). */
  body: string;
  files: GitFileChange[];
  isMerge: boolean;
}

/** Lazy-loaded unified diff for one file within a commit. */
export interface GitFileDiff {
  path: string;
  /** Raw unified-diff text (first-parent vs commit). */
  patch: string;
  binary: boolean;
}

/** A session with a live job streaming right now. */
export interface LiveSession {
  folder: string;
  sessionId: string;
  title: string;
  /** Epoch millis the job started. */
  startedAt: number;
}

// Active-session set: a session is "active" (shown on home) when present here;
// absent ⇒ done (the default). Keyed by sessionId, persisted server-side.
export interface ActiveEntry {
  folder: string;
  title: string;
  /** Epoch millis of the last time it went/stayed active. */
  lastActiveAt: number;
}
export type ActiveMap = Record<string, ActiveEntry>;

/** Custom, user-set session titles keyed by sessionId. Overrides the
 * first-message-derived title and persists across active/done. */
export type TitleMap = Record<string, string>;

/** The fixed palette of folder theme colors. Named (not freeform hex) so each
 * maps to a CSS class whose tint is tuned for the dark theme. */
export const FOLDER_COLORS = [
  "violet",
  "indigo",
  "blue",
  "cyan",
  "teal",
  "green",
  "lime",
  "amber",
  "orange",
  "red",
  "rose",
  "pink",
] as const;
export type FolderColor = (typeof FOLDER_COLORS)[number];

/** Per-folder presentation metadata, keyed by folder path. */
export interface FolderMeta {
  color: FolderColor;
}
export type FolderMetaMap = Record<FolderPath, FolderMeta>;

/** An entry in the server-side directory browser. */
export interface DirEntry {
  name: string;
  path: string;
  /** True if this directory already has Claude session transcripts. */
  hasSessions: boolean;
}

export interface BrowseResult {
  path: string;
  parent: string | null;
  dirs: DirEntry[];
}

/** A node in the inline code editor's file tree. Paths are relative to root. */
export interface FileNode {
  name: string;
  path: string;
  dir: boolean;
  children?: FileNode[];
}

/** A single file loaded into the inline editor. */
export interface FileContent {
  path: string;
  /** Text source for "text"; a data: URL for "image". */
  content: string;
  language: string;
  kind: "text" | "image";
}

/**
 * A raw Claude transcript / stream event. The `claude` CLI emits these as
 * newline-delimited JSON; we keep them opaque and let the renderer pick out
 * the bits it understands.
 */
export interface ClaudeEvent {
  type?: string;
  subtype?: string;
  session_id?: string;
  message?: {
    role?: string;
    content?: unknown;
  };
  // partial-message stream events
  event?: ClaudeEvent;
  delta?: { type?: string; text?: string };
  content_block?: { type?: string };
  index?: number;
  // result events
  result?: string;
  is_error?: boolean;
  timestamp?: string;
  [key: string]: unknown;
}

/**
 * Messages the SSE chat stream sends to the client. Mirrors the behaviour of
 * the original entity's stream kinds, over plain SSE instead of a WS stream.
 */
export type JobStatus = "running" | "done" | "error" | "stopped";

export type ChatStreamMessage =
  | {
      // Full current state of a job — sent first to any (re)connecting client.
      kind: "snapshot";
      jobId: string;
      sessionId: string | null;
      events: ClaudeEvent[];
      status: JobStatus;
      error?: string;
      /** Epoch millis the turn was spawned — for the "answering for Xs" timer. */
      startedAt: number;
    }
  | { kind: "event"; event: ClaudeEvent }
  | { kind: "session-id"; sessionId: string }
  | { kind: "done" }
  | { kind: "error"; message: string };
