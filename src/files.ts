// The files form's reads: a folder's entries and a file's text, live from disk, inside a root Claude
// drew a files block for (src/chat.ts resolves and records it). In a git repo the tree is what git
// sees (tracked + untracked, not ignored) with each file's status; elsewhere, the folder itself.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

export type Entry = { name: string; dir: boolean; status?: string; changed?: number };

const SKIP = new Set([".git", "node_modules", ".shadow-cljs", "target", ".next", ".cache", "dist", "out"]);
const MAX_BYTES = 2_000_000;
const FRESH_MS = 4000;

type Repo = { files: string[]; status: Map<string, string>; at: number } | null;
const repos = new Map<string, Repo>();

function git(root: string, args: string[]): string | null {
  const r = Bun.spawnSync(["git", "-C", root, ...args], { stderr: "ignore" });
  return r.exitCode === 0 ? r.stdout.toString() : null;
}

// The repo view of a root: its files and their status (M changed, A added, U new, D deleted), relative to the root.
function repo(root: string): Repo {
  const hit = repos.get(root);
  if (hit !== undefined && (hit === null || Date.now() - hit.at < FRESH_MS)) return hit;
  const top = git(root, ["rev-parse", "--show-toplevel"])?.trim();
  if (!top) { repos.set(root, null); return null; }
  const files = (git(root, ["ls-files", "-co", "--exclude-standard", "-z"]) ?? "").split("\0").filter(Boolean);
  const status = new Map<string, string>();
  for (const line of (git(root, ["status", "--porcelain=v1", "-z", "--untracked-files=all", "."]) ?? "").split("\0")) {
    if (line.length < 4) continue;
    const code = line.slice(0, 2);
    const path = relative(root, join(top, line.slice(3)));
    if (path.startsWith("..")) continue;
    status.set(path, code === "??" ? "U" : code.includes("D") ? "D" : code.includes("A") ? "A" : "M");
  }
  // Deleted files are gone from disk but still listed by git: keep them visible with their D.
  const r = { files: [...new Set([...files, ...status.keys()])], status, at: Date.now() };
  repos.set(root, r);
  return r;
}

// path inside root, or null when it would leave it.
function inside(root: string, rel: string): string | null {
  const abs = resolve(root, rel || ".");
  return abs === root || abs.startsWith(root + sep) ? abs : null;
}

export function listDir(root: string, rel: string): Entry[] | null {
  const abs = inside(root, rel);
  if (!abs) return null;
  const r = repo(root);
  const out = new Map<string, Entry>();
  if (r) {
    const pre = rel ? `${rel.replace(/\/$/, "")}/` : "";
    for (const f of r.files) {
      if (!f.startsWith(pre)) continue;
      const rest = f.slice(pre.length);
      const slash = rest.indexOf("/");
      const name = slash < 0 ? rest : rest.slice(0, slash);
      const e = out.get(name) ?? { name, dir: slash >= 0 };
      const st = r.status.get(f);
      if (slash < 0) e.status = st;
      else if (st) e.changed = (e.changed ?? 0) + 1;
      out.set(name, e);
    }
  } else {
    try {
      for (const d of readdirSync(abs, { withFileTypes: true })) if (!SKIP.has(d.name)) out.set(d.name, { name: d.name, dir: d.isDirectory() });
    } catch {
      return null;
    }
  }
  return [...out.values()].sort((a, b) => Number(b.dir) - Number(a.dir) || a.name.localeCompare(b.name));
}

export function readText(root: string, rel: string): { text: string } | { error: string } {
  const abs = inside(root, rel);
  if (!abs) return { error: "outside the folder" };
  try {
    const st = statSync(abs);
    if (!st.isFile()) return { error: "not a file" };
    if (st.size > MAX_BYTES) return { error: `too big to show (${Math.round(st.size / 1000)} KB)` };
    const text = readFileSync(abs, "utf8");
    return text.includes("\0") ? { error: "not a text file" } : { text };
  } catch {
    return { error: "can't read it" };
  }
}
