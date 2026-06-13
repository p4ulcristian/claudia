import { NextResponse } from "next/server";
import { listDir } from "@/lib/browse";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const { searchParams } = new URL(req.url);
  const path = searchParams.get("path");
  return NextResponse.json(await listDir(path));
}
