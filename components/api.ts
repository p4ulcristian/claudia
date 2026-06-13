"use client";

import type {
  BrowseResult,
  ClaudeEvent,
  FolderPath,
  SessionSummary,
} from "@/lib/types";

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
