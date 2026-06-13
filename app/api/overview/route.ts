import { NextResponse } from "next/server";
import { listFolders } from "@/lib/folders";
import { listSessions } from "@/lib/sessions";
import { getAllMeta } from "@/lib/session-meta";
import type { OverviewSession } from "@/lib/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// Every session across every folder that the user hasn't marked done — the
// home-screen "in focus" backlog. Newest first.
export async function GET() {
  const [folders, meta] = await Promise.all([listFolders(), getAllMeta()]);
  const perFolder = await Promise.all(
    folders.map(async (folder) =>
      (await listSessions(folder)).map(
        (s): OverviewSession => ({
          folder,
          sessionId: s.sessionId,
          title: s.title,
          modified: s.modified,
        }),
      ),
    ),
  );
  const sessions = perFolder
    .flat()
    .filter((s) => !meta[s.sessionId]?.done)
    .sort((a, b) => b.modified - a.modified);
  return NextResponse.json({ sessions });
}
