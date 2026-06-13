import { resume } from "@/lib/claude-process";
import type { ChatStreamMessage } from "@/lib/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

interface ChatBody {
  folder?: string;
  sessionId?: string | null;
  prompt?: string;
}

export async function POST(req: Request) {
  const body = (await req.json().catch(() => ({}))) as ChatBody;
  const folder = body.folder?.trim();
  const prompt = body.prompt?.trim();

  if (!folder || !prompt) {
    return new Response(
      JSON.stringify({ error: "folder and prompt are required" }),
      { status: 400, headers: { "content-type": "application/json" } },
    );
  }

  const encoder = new TextEncoder();
  const send = (msg: ChatStreamMessage) =>
    encoder.encode(`data: ${JSON.stringify(msg)}\n\n`);

  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      try {
        for await (const msg of resume({
          folder,
          sessionId: body.sessionId ?? null,
          prompt,
          signal: req.signal,
        })) {
          controller.enqueue(send(msg));
        }
      } catch (e) {
        // Client aborts surface here; only report genuine failures.
        if (!req.signal.aborted) {
          controller.enqueue(
            send({
              kind: "error",
              message: e instanceof Error ? e.message : String(e),
            }),
          );
        }
      } finally {
        try {
          controller.close();
        } catch {
          /* already closed */
        }
      }
    },
  });

  return new Response(stream, {
    headers: {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
      "x-accel-buffering": "no",
    },
  });
}
