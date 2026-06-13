"use client";

import type { ChatStreamMessage } from "@/lib/types";

export interface StreamChatParams {
  folder: string;
  sessionId: string | null;
  prompt: string;
  signal: AbortSignal;
}

/**
 * POST a prompt to /api/chat and read the SSE stream, invoking `onMessage`
 * for each parsed message. Resolves when the stream ends; aborting the signal
 * stops the server process and unwinds the read loop.
 */
export async function streamChat(
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
    }),
    signal: params.signal,
  });

  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => "");
    onMessage({ kind: "error", message: text || `${res.status} ${res.statusText}` });
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // SSE frames are separated by a blank line.
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
  } catch (e) {
    // AbortError is expected on stop — don't surface it.
    if ((e as Error)?.name !== "AbortError") {
      onMessage({ kind: "error", message: (e as Error)?.message ?? String(e) });
    }
  } finally {
    reader.releaseLock();
  }
}
