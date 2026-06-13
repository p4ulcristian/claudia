import { NextResponse } from "next/server";
import {
  commitDetail,
  fileDiff,
  isGitRepo,
  repoName,
  smartlog,
  status,
  worktreeFileDiff,
} from "@/lib/git";
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

  // Commit-detail / single-file-diff modes (used by the detail pane).
  const commit = searchParams.get("commit");
  if (commit) {
    try {
      const file = searchParams.get("file");
      if (file) {
        return NextResponse.json(await fileDiff(folder, commit, file));
      }
      return NextResponse.json(await commitDetail(folder, commit));
    } catch (e) {
      return NextResponse.json(
        { error: e instanceof Error ? e.message : String(e) },
        { status: 400 },
      );
    }
  }

  // Working-tree single-file diff mode (used by the working-tree pane).
  if (searchParams.get("worktree")) {
    try {
      const file = searchParams.get("file");
      if (!file) {
        return NextResponse.json({ error: "file required" }, { status: 400 });
      }
      const untracked = searchParams.get("untracked") === "1";
      return NextResponse.json(await worktreeFileDiff(folder, file, untracked));
    } catch (e) {
      return NextResponse.json(
        { error: e instanceof Error ? e.message : String(e) },
        { status: 400 },
      );
    }
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
