import { NextResponse } from "next/server";
import { loadSession } from "@/lib/sessions";

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
  return NextResponse.json({
    sessionId: id,
    events: await loadSession(folder, id),
  });
}
