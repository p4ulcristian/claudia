import { NextResponse } from "next/server";
import { addFolder, listFolders, removeFolder } from "@/lib/folders";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET() {
  return NextResponse.json({ folders: await listFolders() });
}

export async function POST(req: Request) {
  const { path } = await req.json().catch(() => ({ path: "" }));
  return NextResponse.json({ folders: await addFolder(path) });
}

export async function DELETE(req: Request) {
  const { path } = await req.json().catch(() => ({ path: "" }));
  return NextResponse.json({ folders: await removeFolder(path) });
}
