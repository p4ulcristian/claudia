import { NextResponse } from "next/server";
import { setTitle } from "@/lib/session-title";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// Set or clear a session's custom title. An empty/whitespace title clears the
// override, reverting to the first-message-derived title.
export async function POST(req: Request) {
  const body = (await req.json().catch(() => ({}))) as {
    sessionId?: string;
    title?: string;
  };
  if (!body.sessionId) {
    return NextResponse.json({ error: "sessionId required" }, { status: 400 });
  }
  return NextResponse.json({
    titles: await setTitle(body.sessionId, body.title ?? ""),
  });
}
