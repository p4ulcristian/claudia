import { NextResponse } from "next/server";
import { randomUUID } from "node:crypto";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// A fresh id per server process: it's generated once when this module is first
// loaded and stays constant for the life of the process, so it changes exactly
// when the service restarts (e.g. a rebuild-on-start). The client polls this
// and reloads when it sees a new id, so an open tab can't keep running the old
// JS bundle against a freshly rebuilt server (stale chunks → broken UI).
const BOOT_ID = randomUUID();

export async function GET() {
  return NextResponse.json({ bootId: BOOT_ID });
}
