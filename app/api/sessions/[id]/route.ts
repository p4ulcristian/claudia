import { NextResponse } from "next/server";
import { loadSession, loadSessionDelta } from "@/lib/sessions";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(
  req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const { searchParams } = new URL(req.url);
  const folder = searchParams.get("folder");
  if (!folder) {
    return NextResponse.json({ error: "folder query param required" }, { status: 400 });
  }

  // Delta mode: caller passes the byte offset it already has cached.
  const sinceParam = searchParams.get("since");
  if (sinceParam !== null) {
    const since = Number.parseInt(sinceParam, 10) || 0;
    const delta = await loadSessionDelta(folder, id, since);
    return NextResponse.json({ sessionId: id, ...delta });
  }

  return NextResponse.json({
    sessionId: id,
    events: await loadSession(folder, id),
  });
}
