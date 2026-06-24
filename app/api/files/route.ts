import { NextResponse } from "next/server";
import { listTree, readFile, writeFile } from "@/lib/files";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// GET ?folder=<root>            → file tree
// GET ?folder=<root>&path=<rel> → single file contents + language
export async function GET(req: Request) {
  const { searchParams } = new URL(req.url);
  const folder = searchParams.get("folder");
  if (!folder) {
    return NextResponse.json(
      { error: "folder query param required" },
      { status: 400 },
    );
  }
  try {
    const rel = searchParams.get("path");
    if (rel) {
      return NextResponse.json(await readFile(folder, rel));
    }
    return NextResponse.json({ tree: await listTree(folder) });
  } catch (e) {
    return NextResponse.json(
      { error: e instanceof Error ? e.message : String(e) },
      { status: 400 },
    );
  }
}

// PUT ?folder=<root>  body {path, content} → write file to disk
export async function PUT(req: Request) {
  const { searchParams } = new URL(req.url);
  const folder = searchParams.get("folder");
  if (!folder) {
    return NextResponse.json(
      { error: "folder query param required" },
      { status: 400 },
    );
  }
  try {
    const body = (await req.json()) as { path?: string; content?: string };
    if (typeof body.path !== "string" || typeof body.content !== "string") {
      return NextResponse.json(
        { error: "path and content required" },
        { status: 400 },
      );
    }
    await writeFile(folder, body.path, body.content);
    return NextResponse.json({ ok: true });
  } catch (e) {
    return NextResponse.json(
      { error: e instanceof Error ? e.message : String(e) },
      { status: 400 },
    );
  }
}
