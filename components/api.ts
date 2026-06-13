"use client";

import type {
  BrowseResult,
  ClaudeEvent,
  FolderPath,
  GitData,
  LiveSession,
  SessionMeta,
  SessionSummary,
  TranscriptDelta,
} from "@/lib/types";
import type { UsageData } from "@/lib/usage";

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json() as Promise<T>;
}

export async function getFolders(): Promise<FolderPath[]> {
  return (await json<{ folders: FolderPath[] }>(await fetch("/api/folders"))).folders;
}

export async function addFolder(path: string): Promise<FolderPath[]> {
  return (
    await json<{ folders: FolderPath[] }>(
      await fetch("/api/folders", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ path }),
      }),
    )
  ).folders;
}

export async function removeFolder(path: string): Promise<FolderPath[]> {
  return (
    await json<{ folders: FolderPath[] }>(
      await fetch("/api/folders", {
        method: "DELETE",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ path }),
      }),
    )
  ).folders;
}

export async function browse(path: string | null): Promise<BrowseResult> {
  const qs = path ? `?path=${encodeURIComponent(path)}` : "";
  return json<BrowseResult>(await fetch(`/api/browse${qs}`));
}

export async function getSessions(folder: string): Promise<SessionSummary[]> {
  return (
    await json<{ sessions: SessionSummary[] }>(
      await fetch(`/api/sessions?folder=${encodeURIComponent(folder)}`),
    )
  ).sessions;
}

export async function loadSession(
  folder: string,
  sessionId: string,
): Promise<ClaudeEvent[]> {
  return (
    await json<{ events: ClaudeEvent[] }>(
      await fetch(
        `/api/sessions/${encodeURIComponent(sessionId)}?folder=${encodeURIComponent(folder)}`,
      ),
    )
  ).events;
}

/** Load only the transcript events appended past `since` bytes. */
export async function loadSessionDelta(
  folder: string,
  sessionId: string,
  since: number,
): Promise<TranscriptDelta> {
  return json<TranscriptDelta & { sessionId: string }>(
    await fetch(
      `/api/sessions/${encodeURIComponent(sessionId)}?folder=${encodeURIComponent(
        folder,
      )}&since=${since}`,
    ),
  );
}

// ---- session-list cache (localStorage; the list is tiny) ----

const listKey = (folder: string) => `claudia-sessions:${folder}`;

export function readSessionListCache(folder: string): SessionSummary[] | null {
  try {
    const raw = localStorage.getItem(listKey(folder));
    return raw ? (JSON.parse(raw) as SessionSummary[]) : null;
  } catch {
    return null;
  }
}

export function writeSessionListCache(
  folder: string,
  sessions: SessionSummary[],
): void {
  try {
    localStorage.setItem(listKey(folder), JSON.stringify(sessions));
  } catch {
    // quota / unavailable — cache is best-effort
  }
}

/** Git smartlog + working-tree status for a folder. */
export async function getGit(folder: string): Promise<GitData> {
  return json<GitData>(
    await fetch(`/api/git?folder=${encodeURIComponent(folder)}`),
  );
}

/** Sessions with a live job streaming right now, across all folders. */
export async function getLive(): Promise<LiveSession[]> {
  return (await json<{ sessions: LiveSession[] }>(await fetch("/api/live"))).sessions;
}

/** sessionId → triage meta (done flag). */
export async function getSessionMeta(): Promise<Record<string, SessionMeta>> {
  return (
    await json<{ meta: Record<string, SessionMeta> }>(await fetch("/api/session-meta"))
  ).meta;
}

export async function setSessionDone(
  sessionId: string,
  done: boolean,
): Promise<Record<string, SessionMeta>> {
  return (
    await json<{ meta: Record<string, SessionMeta> }>(
      await fetch("/api/session-meta", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ sessionId, done }),
      }),
    )
  ).meta;
}

/** Permanently delete a session's transcript file. */
export async function deleteSession(
  folder: string,
  sessionId: string,
): Promise<void> {
  await json<{ ok: boolean }>(
    await fetch(
      `/api/sessions/${encodeURIComponent(sessionId)}?folder=${encodeURIComponent(folder)}`,
      { method: "DELETE" },
    ),
  );
}

export async function getUsage(refresh = false): Promise<UsageData> {
  return json<UsageData>(await fetch(`/api/usage${refresh ? "?refresh=1" : ""}`));
}
