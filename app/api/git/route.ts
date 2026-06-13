import { NextResponse } from "next/server";
import { isGitRepo, repoName, smartlog, status } from "@/lib/git";
import type { GitData } from "@/lib/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const { searchParams } = new URL(req.url);
  const folder = searchParams.get("folder");
  if (!folder) {
    return NextResponse.json(
      { error: "folder query param required" },
      { status: 400 },
    );
  }

  const repo = { name: repoName(folder), path: folder };

  if (!(await isGitRepo(folder))) {
    return NextResponse.json({
      repo,
      commits: [],
      head: "",
      currentBranch: "",
      detached: false,
      status: { ahead: 0, behind: 0, files: [] },
      error: "Not a git repository.",
    } satisfies GitData);
  }

  try {
    const [log, st] = await Promise.all([smartlog(folder), status(folder)]);
    return NextResponse.json({ repo, ...log, status: st } satisfies GitData);
  } catch (e) {
    return NextResponse.json({
      repo,
      commits: [],
      head: "",
      currentBranch: "",
      detached: false,
      status: { ahead: 0, behind: 0, files: [] },
      error: e instanceof Error ? e.message : String(e),
    } satisfies GitData);
  }
}
