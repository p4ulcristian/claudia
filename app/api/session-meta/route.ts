import { NextResponse } from "next/server";
import { getAllMeta, setDone } from "@/lib/session-meta";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json({ meta: await getAllMeta() });
}

export async function POST(req: Request) {
  const body = (await req.json().catch(() => ({}))) as {
    sessionId?: string;
    done?: boolean;
  };
  if (!body.sessionId) {
    return NextResponse.json({ error: "sessionId required" }, { status: 400 });
  }
  return NextResponse.json({ meta: await setDone(body.sessionId, body.done === true) });
}
