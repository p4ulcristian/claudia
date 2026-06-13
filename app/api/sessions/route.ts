import { NextResponse } from "next/server";
import { listSessions } from "@/lib/sessions";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const { searchParams } = new URL(req.url);
  const folder = searchParams.get("folder");
  if (!folder) {
    return NextResponse.json({ error: "folder query param required" }, { status: 400 });
  }
  return NextResponse.json({ folder, sessions: await listSessions(folder) });
}
