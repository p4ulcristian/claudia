"use client";

import type { ChatStreamMessage } from "@/lib/types";

export interface StreamChatParams {
  folder: string;
  sessionId: string | null;
  prompt: string;
  model: string;
  signal: AbortSignal;
}

/** Parse an SSE response body, invoking `onMessage` per frame, until it ends. */
async function readSse(
  res: Response,
  onMessage: (msg: ChatStreamMessage) => void,
): Promise<void> {
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let sep: number;
      while ((sep = buffer.indexOf("\n\n")) !== -1) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        const line = frame.split("\n").find((l) => l.startsWith("data:"));
        if (!line) continue;
        const payload = line.slice(5).trim();
        if (!payload) continue;
        try {
          onMessage(JSON.parse(payload) as ChatStreamMessage);
        } catch {
          /* ignore malformed frame */
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/**
 * Start a new generation turn and stream it. The job runs server-side and
 * survives this request; aborting `signal` only detaches the reader.
 */
export async function startChat(
  params: StreamChatParams,
  onMessage: (msg: ChatStreamMessage) => void,
): Promise<void> {
  const res = await fetch("/api/chat", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      folder: params.folder,
      sessionId: params.sessionId,
      prompt: params.prompt,
      model: params.model,
    }),
    signal: params.signal,
  });
  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => "");
    onMessage({ kind: "error", message: text || `${res.status} ${res.statusText}` });
    return;
  }
  try {
    await readSse(res, onMessage);
  } catch (e) {
    if ((e as Error)?.name !== "AbortError") {
      onMessage({ kind: "error", message: (e as Error)?.message ?? String(e) });
    }
  }
}

/**
 * Reconnect to a running job for a session. Resolves to true if one was live
 * (and is now streaming via `onMessage`), false if nothing is running.
 */
export async function subscribeChat(
  session: string,
  onMessage: (msg: ChatStreamMessage) => void,
  signal: AbortSignal,
): Promise<boolean> {
  const res = await fetch(`/api/chat?session=${encodeURIComponent(session)}`, { signal });
  if (res.status === 204 || !res.ok || !res.body) return false;
  // Read in the background; resolve now so the caller knows it's live.
  void readSse(res, onMessage).catch((e) => {
    if ((e as Error)?.name !== "AbortError") {
      onMessage({ kind: "error", message: (e as Error)?.message ?? String(e) });
    }
  });
  return true;
}

/** Explicitly kill a running job (Stop). */
export async function stopChat(ref: { jobId?: string; session?: string }): Promise<void> {
  await fetch("/api/chat/stop", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(ref),
  }).catch(() => {});
}
