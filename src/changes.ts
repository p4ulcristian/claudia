// File changes per turn: a copy before Claude first touches a file, compared after the turn.
import { diffLines } from "diff";
import { stat } from "node:fs/promises";
import { dirname } from "node:path";

export const EDIT_TOOLS = "Write|Edit|MultiEdit|NotebookEdit";
const MAX_BYTES = 1_000_000;

export type FileState = { text: string | null; skipped?: string };
export type ChangeMeta = {
  id: number;
  turn: string;
  path: string;
  added: number;
  removed: number;
  status: "added" | "deleted" | "modified";
  skipped?: string;
};

export function editedPath(input: any): string | null {
  const p = input?.file_path ?? input?.notebook_path;
  return typeof p === "string" && p.startsWith("/") ? p : null;
}

// The file as it is now; null text = it doesn't exist. Big or binary files are not kept.
export async function readState(path: string): Promise<FileState> {
  try {
    const s = await stat(path);
    if (!s.isFile()) return { text: null };
    if (s.size > MAX_BYTES) return { text: null, skipped: "too large to show" };
    const buf = Buffer.from(await Bun.file(path).arrayBuffer());
    if (buf.includes(0)) return { text: null, skipped: "binary file" };
    return { text: buf.toString("utf8") };
  } catch {
    return { text: null };
  }
}

export function lineCounts(before: string | null, after: string | null) {
  let added = 0, removed = 0;
  for (const part of diffLines(before ?? "", after ?? "")) {
    if (part.added) added += part.count ?? 0;
    if (part.removed) removed += part.count ?? 0;
  }
  return { added, removed };
}

// The project a file belongs to: its git root, else its own folder.
export function projectOf(path: string): string {
  const dir = dirname(path);
  const r = Bun.spawnSync(["git", "-C", dir, "rev-parse", "--show-toplevel"], { stderr: "ignore" });
  const root = r.success ? r.stdout.toString().trim() : "";
  return root || dir;
}

// Whole-folder snapshots for git repos, so edits made with shell commands count too.
// The snapshot is a git tree written through a separate index file: the user's index,
// branches and stash are never touched; it only adds objects to the repo's object store.
async function git(root: string, args: string[], env: Record<string, string> = {}) {
  const p = Bun.spawn(["git", "-C", root, ...args], { env: { ...process.env, ...env }, stdout: "pipe", stderr: "ignore" });
  const out = await new Response(p.stdout).text();
  return (await p.exited) === 0 ? out : null;
}

// The git repo a folder is in, if any.
export async function repoRoot(dir: string) {
  return (await git(dir, ["rev-parse", "--show-toplevel"]))?.trim() || null;
}

export async function snapshotTree(root: string, indexFile: string): Promise<string | null> {
  const real = (await git(root, ["rev-parse", "--path-format=absolute", "--git-path", "index"]))?.trim();
  // Start from a copy of the real index: its file stats let git hash only what changed.
  if (real && (await Bun.file(real).exists())) await Bun.write(indexFile, Bun.file(real));
  else await Bun.write(indexFile, "");
  const env = { GIT_INDEX_FILE: indexFile };
  if ((await git(root, ["add", "-A"], env)) === null) return null;
  return (await git(root, ["write-tree"], env))?.trim() || null;
}

export async function treeChanges(root: string, a: string, b: string, limit = 200) {
  const out = await git(root, ["diff-tree", "-r", "--no-renames", "--name-only", "-z", a, b]);
  return (out ?? "").split("\0").filter(Boolean).slice(0, limit).map((rel) => ({ rel, abs: `${root}/${rel}` }));
}

export async function blobState(root: string, tree: string, rel: string): Promise<FileState> {
  const size = Number((await git(root, ["cat-file", "-s", `${tree}:${rel}`]))?.trim());
  if (!Number.isFinite(size) || Number.isNaN(size)) return { text: null };
  if (size > MAX_BYTES) return { text: null, skipped: "too large to show" };
  const p = Bun.spawn(["git", "-C", root, "cat-file", "blob", `${tree}:${rel}`], { stdout: "pipe", stderr: "ignore" });
  const buf = Buffer.from(await new Response(p.stdout).arrayBuffer());
  if ((await p.exited) !== 0) return { text: null };
  if (buf.includes(0)) return { text: null, skipped: "binary file" };
  return { text: buf.toString("utf8") };
}
