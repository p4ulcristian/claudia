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
    }
  | { kind: "event"; event: ClaudeEvent }
  | { kind: "session-id"; sessionId: string }
  | { kind: "done" }
  | { kind: "error"; message: string };
