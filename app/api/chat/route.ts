import {
  getJobBySession,
  isEnded,
  snapshotOf,
  startJob,
  subscribe,
} from "@/lib/jobs";
import type { ChatStreamMessage } from "@/lib/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

interface ChatBody {
  folder?: string;
  sessionId?: string | null;
  prompt?: string;
}

const SSE_HEADERS = {
  "content-type": "text/event-stream; charset=utf-8",
  "cache-control": "no-cache, no-transform",
  connection: "keep-alive",
  "x-accel-buffering": "no",
};

type Job = NonNullable<ReturnType<typeof getJobBySession>>;

/**
 * SSE stream of a job: a snapshot first, then live messages until it ends.
 * Cancelling (client disconnect) only unsubscribes — the job keeps running.
 */
function streamJob(job: Job): Response {
  const encoder = new TextEncoder();
  let unsubscribe = () => {};

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const send = (msg: ChatStreamMessage) => {
        try {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(msg)}\n\n`));
        } catch {
          /* already closed */
        }
        if (msg.kind === "done") {
          unsubscribe();
          try {
            controller.close();
          } catch {
            /* already closed */
          }
        }
      };

      unsubscribe = subscribe(job, send);
      send(snapshotOf(job));
      // If it already finished, no further messages will come — close now.
      if (isEnded(job)) {
        unsubscribe();
        try {
          controller.close();
        } catch {
          /* already closed */
        }
      }
    },
    cancel() {
      // Client went away (refresh / navigation). Detach only; do NOT stop.
      unsubscribe();
    },
  });

  return new Response(stream, { headers: SSE_HEADERS });
}

/** Start a new generation turn and stream it. */
export async function POST(req: Request) {
  const body = (await req.json().catch(() => ({}))) as ChatBody;
  const folder = body.folder?.trim();
  const prompt = body.prompt?.trim();
  if (!folder || !prompt) {
    return new Response(JSON.stringify({ error: "folder and prompt are required" }), {
      status: 400,
      headers: { "content-type": "application/json" },
    });
  }

  const job = await startJob({
    folder,
    sessionId: body.sessionId ?? null,
    prompt,
  });
  return streamJob(job);
}

/** Reconnect to a running job for a session (or 204 if none is live). */
export async function GET(req: Request) {
  const session = new URL(req.url).searchParams.get("session");
  if (!session) {
    return new Response(JSON.stringify({ error: "session is required" }), {
      status: 400,
      headers: { "content-type": "application/json" },
    });
  }
  const job = getJobBySession(session);
  if (!job) return new Response(null, { status: 204 });
  return streamJob(job);
}
