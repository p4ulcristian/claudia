import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import type {
  GitCommit,
  GitRef,
  GitSmartlog,
  GitStatus,
  GitStatusFile,
} from "./types";

const execFileAsync = promisify(execFile);

// Run a git command inside `repoPath`. Returns trimmed stdout.
// Throws an Error with .stderr on failure. Array args — no shell, no injection.
export async function git(repoPath: string, args: string[]): Promise<string> {
  try {
    const { stdout } = await execFileAsync("git", ["-C", repoPath, ...args], {
      maxBuffer: 1024 * 1024 * 20,
    });
    return stdout;
  } catch (err) {
    const e = err as { stderr?: string; message?: string };
    throw new Error(e.stderr?.trim() || e.message || "git failed");
  }
}

// Is `dir` inside a git working tree?
export async function isGitRepo(dir: string): Promise<boolean> {
  try {
    const out = await git(dir, ["rev-parse", "--is-inside-work-tree"]);
    return out.trim() === "true";
  } catch {
    return false;
  }
}

// Short name for a repo = its folder name.
export function repoName(repoPath: string): string {
  return path.basename(repoPath.replace(/\/+$/, "")) || repoPath;
}

const NUL = "\x1f"; // unit separator between fields (argv-safe; not a null byte)
const REC = "\x1e"; // record separator between commits

// Build smartlog data: commits with graph lane positions, refs, and HEAD.
export async function smartlog(
  repoPath: string,
  limit = 300,
): Promise<GitSmartlog> {
  let head = "";
  let currentBranch = "";
  try {
    head = (await git(repoPath, ["rev-parse", "HEAD"])).trim();
  } catch {
    // empty repo with no commits
    return { commits: [], head: "", currentBranch: "", detached: false };
  }
  try {
    currentBranch = (
      await git(repoPath, ["symbolic-ref", "--quiet", "--short", "HEAD"])
    ).trim();
  } catch {
    currentBranch = ""; // detached HEAD
  }

  const fmt = ["%H", "%P", "%an", "%ar", "%D", "%s"].join(NUL) + REC;
  const out = await git(repoPath, [
    "log",
    "--branches",
    "--tags",
    "--remotes",
    "HEAD",
    "--topo-order",
    `--max-count=${limit}`,
    `--pretty=format:${fmt}`,
  ]);

  const commits: GitCommit[] = out
    .split(REC)
    .map((r) => r.trim())
    .filter(Boolean)
    .map((rec) => {
      const [hash, parents, author, when, refs, subject] = rec.split(NUL);
      return {
        hash,
        parents: parents ? parents.split(" ").filter(Boolean) : [],
        author,
        when,
        refs: parseRefs(refs),
        subject,
        col: 0,
      };
    });

  assignLanes(commits);

  return {
    commits,
    head,
    currentBranch,
    detached: head !== "" && currentBranch === "",
  };
}

// Turn "HEAD -> main, origin/main, tag: v1" into structured refs.
function parseRefs(refsStr: string): GitRef[] {
  if (!refsStr) return [];
  return refsStr
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((r): GitRef => {
      if (r.startsWith("HEAD -> ")) return { type: "head", name: r.slice(8) };
      if (r === "HEAD") return { type: "detached", name: "HEAD" };
      if (r.startsWith("tag: ")) return { type: "tag", name: r.slice(5) };
      if (r.startsWith("origin/") || r.includes("/"))
        return { type: "remote", name: r };
      return { type: "branch", name: r };
    });
}

// Classic smartlog lane assignment. Commits are newest-first, children
// before parents (topo order). Mutates each commit, adding `col`.
function assignLanes(commits: GitCommit[]): void {
  const lanes: (string | null)[] = []; // each slot holds the hash that lane is "waiting" for

  const firstFree = () => {
    const i = lanes.indexOf(null);
    return i === -1 ? lanes.push(null) - 1 : i;
  };

  for (const c of commits) {
    let col = lanes.indexOf(c.hash);
    if (col === -1) {
      col = firstFree();
    }
    // a merge can have several lanes waiting on this commit — collapse them
    for (let i = 0; i < lanes.length; i++) {
      if (i !== col && lanes[i] === c.hash) lanes[i] = null;
    }
    c.col = col;

    if (c.parents.length === 0) {
      lanes[col] = null;
    } else {
      lanes[col] = c.parents[0]; // first parent continues this lane
      for (let k = 1; k < c.parents.length; k++) {
        if (lanes.indexOf(c.parents[k]) === -1) {
          lanes[firstFree()] = c.parents[k];
        }
      }
    }
  }
}

// Working-tree status: changed/staged/untracked files + ahead/behind upstream.
export async function status(repoPath: string): Promise<GitStatus> {
  const out = await git(repoPath, ["status", "--porcelain=v1", "--branch"]);
  const files: GitStatusFile[] = [];
  let ahead = 0;
  let behind = 0;
  for (const line of out.split("\n")) {
    if (!line) continue;
    if (line.startsWith("##")) {
      ahead = Number(line.match(/ahead (\d+)/)?.[1] ?? 0);
      behind = Number(line.match(/behind (\d+)/)?.[1] ?? 0);
      continue;
    }
    const x = line[0];
    const y = line[1];
    let file = line.slice(3);
    // renames/copies are "old -> new"; show the new path
    const arrow = file.indexOf(" -> ");
    if (arrow !== -1) file = file.slice(arrow + 4);
    files.push({ x, y, path: file });
  }
  return { ahead, behind, files };
}
