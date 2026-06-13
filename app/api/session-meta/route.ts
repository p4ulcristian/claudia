import { NextResponse } from "next/server";
import { clearActive, getActive, setActive } from "@/lib/session-meta";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json({ active: await getActive() });
}

export async function POST(req: Request) {
  const body = (await req.json().catch(() => ({}))) as {
    sessionId?: string;
    active?: boolean;
    folder?: string;
    title?: string;
  };
  if (!body.sessionId) {
    return NextResponse.json({ error: "sessionId required" }, { status: 400 });
  }
  if (body.active) {
    if (!body.folder) {
      return NextResponse.json(
        { error: "folder required to activate" },
        { status: 400 },
      );
    }
    return NextResponse.json({
      active: await setActive(
        body.sessionId,
        body.folder,
        body.title ?? "(untitled)",
        Date.now(),
      ),
    });
  }
  return NextResponse.json({ active: await clearActive(body.sessionId) });
}
