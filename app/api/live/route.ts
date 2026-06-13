import { NextResponse } from "next/server";
import { listJobs } from "@/lib/jobs";
import type { ClaudeEvent, LiveSession } from "@/lib/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// First human message in a job's events, for a label. Mirrors the client-side
// titleFromEvents / server firstUserText.
function titleFromEvents(events: ClaudeEvent[]): string {
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
    if (t && !t.startsWith("<")) return t.length > 80 ? `${t.slice(0, 80)}…` : t;
  }
  return "(no title)";
}

export async function GET() {
  const sessions: LiveSession[] = listJobs()
    .filter((j) => j.status === "running" && j.sessionId)
    .map((j) => ({
      folder: j.folder,
      sessionId: j.sessionId as string,
      title: titleFromEvents(j.events),
      startedAt: j.startedAt,
    }));
  return NextResponse.json({ sessions });
}
