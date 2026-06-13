import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import type {
  GitCommit,
  GitCommitDetail,
  GitFileChange,
  GitFileDiff,
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

// The well-known empty-tree object, used as the diff base for root commits
// (commits with no parent) so they go through the same `git diff` path.
const EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

// Reject anything that isn't a plain hex object id, so a hash can never be
// mistaken for a git option (e.g. "--upload-pack=…") when spliced into argv.
function assertHash(hash: string): void {
  if (!/^[0-9a-f]{7,40}$/i.test(hash)) {
    throw new Error(`bad commit id: ${hash}`);
  }
}

// Full detail for one commit: metadata + the files it changed, with per-file
// line counts and status. Diffed against the first parent (empty tree for the
// root commit), which keeps merges/root/normal commits on one uniform path.
export async function commitDetail(
  repoPath: string,
  hash: string,
): Promise<GitCommitDetail> {
  assertHash(hash);

  const fmt = ["%H", "%P", "%an", "%ae", "%ar", "%ad", "%s", "%b"].join(NUL);
  const meta = await git(repoPath, [
    "show",
    "-s",
    "--date=format:%Y-%m-%d %H:%M",
    `--format=${fmt}`,
    hash,
  ]);
  const [h, parentStr, author, authorEmail, when, date, subject, body] =
    meta.split(NUL);
  const parents = parentStr ? parentStr.split(" ").filter(Boolean) : [];
  const base = parents[0] ?? EMPTY_TREE;

  const files = await diffFiles(repoPath, base, hash);

  return {
    hash: h,
    parents,
    author,
    authorEmail,
    when,
    date,
    subject,
    body: (body ?? "").trim(),
    files,
    isMerge: parents.length > 1,
  };
}

// Changed files between two trees, with status letters and line counts.
// `--name-status` and `--numstat` list files in the same order, so we zip them
// by index — sidestepping the awkward rename-path encoding in numstat.
async function diffFiles(
  repoPath: string,
  base: string,
  hash: string,
): Promise<GitFileChange[]> {
  const common = ["diff", "-M", "-z", "--format="];
  const [nameOut, numOut] = await Promise.all([
    git(repoPath, [...common, "--name-status", base, hash]),
    git(repoPath, [...common, "--numstat", base, hash]),
  ]);

  // --name-status -z: STATUS \0 path \0   (renames: STATUS \0 old \0 new \0)
  const nameToks = nameOut.split("\0");
  const statuses: { status: string; path: string; oldPath?: string }[] = [];
  for (let i = 0; i < nameToks.length; ) {
    const code = nameToks[i];
    if (!code) {
      i++;
      continue;
    }
    const letter = code[0];
    if (letter === "R" || letter === "C") {
      statuses.push({ status: letter, oldPath: nameToks[i + 1], path: nameToks[i + 2] });
      i += 3;
    } else {
      statuses.push({ status: letter, path: nameToks[i + 1] });
      i += 2;
    }
  }

  // --numstat -z: adds \t dels \t path \0   (renames: adds \t dels \0 old \0 new \0)
  const numToks = numOut.split("\0");
  const counts: { additions: number; deletions: number }[] = [];
  for (let i = 0; i < numToks.length; ) {
    const tok = numToks[i];
    if (!tok) {
      i++;
      continue;
    }
    const [a, d, rest] = tok.split("\t");
    const additions = a === "-" ? -1 : Number(a);
    const deletions = d === "-" ? -1 : Number(d);
    counts.push({ additions, deletions });
    // rest is the path for normal entries; empty for renames (paths follow as
    // two more NUL tokens we skip — order already captured by name-status).
    i += rest ? 1 : 3;
  }

  return statuses.map((s, idx) => ({
    ...s,
    additions: counts[idx]?.additions ?? 0,
    deletions: counts[idx]?.deletions ?? 0,
  }));
}

// Unified diff for a single file within a commit (first-parent vs commit).
export async function fileDiff(
  repoPath: string,
  hash: string,
  file: string,
): Promise<GitFileDiff> {
  assertHash(hash);
  const parentStr = (
    await git(repoPath, ["show", "-s", "--format=%P", hash])
  ).trim();
  const base = parentStr.split(" ").filter(Boolean)[0] ?? EMPTY_TREE;

  // `--` terminates options, so a path starting with "-" can't be an option.
  const patch = await git(repoPath, [
    "diff",
    "-M",
    base,
    hash,
    "--",
    file,
  ]);
  return { path: file, patch, binary: /^Binary files /m.test(patch) };
}

// Unified diff for one working-tree file: everything not yet committed.
// Tracked files diff against HEAD (staged + unstaged combined, matching the
// collapsed badge in the UI). Untracked files have no HEAD blob, so we diff the
// empty side against the file with `--no-index`, which renders it all-added.
export async function worktreeFileDiff(
  repoPath: string,
  file: string,
  untracked: boolean,
): Promise<GitFileDiff> {
  let patch: string;
  if (untracked) {
    patch = await gitDiffNoIndex(repoPath, [
      "diff",
      "--no-index",
      "--",
      "/dev/null",
      file,
    ]);
  } else {
    // `--` terminates options, so a path starting with "-" can't be an option.
    patch = await git(repoPath, ["diff", "HEAD", "--", file]);
  }
  return { path: file, patch, binary: /^Binary files /m.test(patch) };
}

// Like git(), but tolerates exit code 1. `git diff --no-index` implies
// --exit-code and returns 1 simply because the files differ — that's success
// for our purposes, and the patch is on stdout.
async function gitDiffNoIndex(
  repoPath: string,
  args: string[],
): Promise<string> {
  try {
    const { stdout } = await execFileAsync("git", ["-C", repoPath, ...args], {
      maxBuffer: 1024 * 1024 * 20,
    });
    return stdout;
  } catch (err) {
    const e = err as { code?: number; stdout?: string; stderr?: string };
    if (e.code === 1 && typeof e.stdout === "string") return e.stdout;
    throw new Error(e.stderr?.trim() || "git diff failed");
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
