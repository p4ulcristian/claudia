"use client";

import type {
  BrowseResult,
  ClaudeEvent,
  FolderPath,
  FolderMetaMap,
  ActiveMap,
  GitCommitDetail,
  GitData,
  GitFileDiff,
  LiveSession,
  SessionSummary,
  TitleMap,
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

/** Per-folder theme metadata (colors), keyed by folder path. */
export async function getFolderMeta(): Promise<FolderMetaMap> {
  return (
    await json<{ folderMeta: FolderMetaMap }>(await fetch("/api/folder-meta"))
  ).folderMeta;
}

/** Set (or clear, with a null color) a folder's theme color. */
export async function setFolderColor(
  folder: string,
  color: string | null,
): Promise<FolderMetaMap> {
  return (
    await json<{ folderMeta: FolderMetaMap }>(
      await fetch("/api/folder-meta", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ folder, color }),
      }),
    )
  ).folderMeta;
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

/** Files + metadata for a single commit. */
export async function getCommit(
  folder: string,
  hash: string,
): Promise<GitCommitDetail> {
  return json<GitCommitDetail>(
    await fetch(
      `/api/git?folder=${encodeURIComponent(folder)}&commit=${encodeURIComponent(hash)}`,
    ),
  );
}

/** Lazy-loaded unified diff for one file within a commit. */
export async function getCommitFileDiff(
  folder: string,
  hash: string,
  file: string,
): Promise<GitFileDiff> {
  return json<GitFileDiff>(
    await fetch(
      `/api/git?folder=${encodeURIComponent(folder)}&commit=${encodeURIComponent(
        hash,
      )}&file=${encodeURIComponent(file)}`,
    ),
  );
}

/** Lazy-loaded unified diff for one working-tree file (uncommitted changes). */
export async function getWorktreeFileDiff(
  folder: string,
  file: string,
  untracked: boolean,
): Promise<GitFileDiff> {
  return json<GitFileDiff>(
    await fetch(
      `/api/git?folder=${encodeURIComponent(folder)}&worktree=1&file=${encodeURIComponent(
        file,
      )}${untracked ? "&untracked=1" : ""}`,
    ),
  );
}

/** Sessions with a live job streaming right now, across all folders. */
export async function getLive(): Promise<LiveSession[]> {
  return (await json<{ sessions: LiveSession[] }>(await fetch("/api/live"))).sessions;
}

/** The active-session set plus custom titles, in one fetch. */
export async function getSessionMeta(): Promise<{ active: ActiveMap; titles: TitleMap }> {
  const { active, titles } = await json<{ active: ActiveMap; titles?: TitleMap }>(
    await fetch("/api/session-meta"),
  );
  return { active, titles: titles ?? {} };
}

/** Set (or clear, with an empty title) a session's custom title. */
export async function setSessionTitle(
  sessionId: string,
  title: string,
): Promise<TitleMap> {
  return (
    await json<{ titles: TitleMap }>(
      await fetch("/api/session-title", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ sessionId, title }),
      }),
    )
  ).titles;
}

/** Mark a session active (folder+title required) or done (active=false). */
export async function setSessionActive(
  sessionId: string,
  active: boolean,
  folder?: string,
  title?: string,
): Promise<ActiveMap> {
  return (
    await json<{ active: ActiveMap }>(
      await fetch("/api/session-meta", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ sessionId, active, folder, title }),
      }),
    )
  ).active;
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
