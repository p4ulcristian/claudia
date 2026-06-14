import { NextResponse } from "next/server";
import { getFolderMeta, setFolderColor } from "@/lib/folder-meta";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json({ folderMeta: await getFolderMeta() });
}

export async function POST(req: Request) {
  const body = (await req.json().catch(() => ({}))) as {
    folder?: string;
    color?: string | null;
  };
  if (!body.folder) {
    return NextResponse.json({ error: "folder required" }, { status: 400 });
  }
  return NextResponse.json({
    folderMeta: await setFolderColor(body.folder, body.color ?? null),
  });
}
