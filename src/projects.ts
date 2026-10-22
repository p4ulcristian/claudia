// A chat's project, from its working folder: the name the rail's tag shows, and the project's own
// logo when it has one (a favicon, app icon or logo file in the repo).
import { existsSync, readdirSync, statSync } from "node:fs";
import { basename, dirname, join, relative, sep } from "node:path";

export type Project = { name: string; root: string; logo: string | null };

const WORK = process.env.CLAUDIA_CWD ?? join(process.env.HOME ?? "/", "Work");
const RESCAN_MS = 10 * 60_000;
const byFolder = new Map<string, { at: number; project: Project }>();
const byName = new Map<string, Project>();

// The project folder: the git repo the folder is in. A worktree (.git is a file) belongs to the
// repo around it, or else to the folder holding the worktrees (apps/ironrainbow/feat-x → ironrainbow).
// Without git, inside ~/Work: the project folder under its group (~/Work/apps/<project>).
function rootOf(folder: string): { root: string; name: string } {
  for (let dir = folder; dir !== dirname(dir); dir = dirname(dir)) {
    const git = join(dir, ".git");
    if (!existsSync(git)) continue;
    if (statSync(git).isDirectory()) return { root: dir, name: basename(dir) };
    for (let up = dirname(dir); up !== dirname(up); up = dirname(up))
      if (existsSync(join(up, ".git")) && statSync(join(up, ".git")).isDirectory()) return { root: dir, name: basename(up) };
    return { root: dir, name: basename(dirname(dir)) };
  }
  const rel = relative(WORK, folder);
  if (rel && !rel.startsWith("..")) {
    const [group, project] = rel.split(sep);
    if (project) return { root: join(WORK, group, project), name: project };
  }
  return { root: folder, name: basename(folder) };
}

// Logo files, best first; the rest of a name scores 0 and isn't a logo.
const NAMES: [RegExp, number][] = [
  [/^favicon\.svg$/i, 100],
  [/^icon\.svg$/i, 95],
  [/^logo-mark\.svg$/i, 92],
  [/^logo\.svg$/i, 85],
  [/^apple-touch-icon\.png$/i, 80],
  [/^apple-icon\.png$/i, 78],
  [/^icon\.png$/i, 75],
  [/^favicon(-\d+)?\.png$/i, 70],
  [/^logo\.png$/i, 60],
  [/^logo\.webp$/i, 55],
  [/^favicon\.ico$/i, 50],
];
const SKIP = new Set(["node_modules", ".git", "target", ".shadow-cljs", "dist", "build", "out", ".next", "vendor", "data", "reference", "test", "tests", "fixtures", "logos", ".cache", "tmp"]);
const MAX_DEPTH = 6;
const MAX_BYTES = 1_000_000;

function findLogo(root: string): string | null {
  let best: { path: string; score: number } | null = null;
  const walk = (dir: string, depth: number) => {
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      if (e.isDirectory()) {
        if (depth < MAX_DEPTH && !SKIP.has(e.name) && !e.name.startsWith(".")) walk(join(dir, e.name), depth + 1);
        continue;
      }
      const hit = NAMES.find(([re]) => re.test(e.name));
      if (!hit) continue;
      const path = join(dir, e.name);
      const score = hit[1] - depth; // shallower wins between equals
      if (best && best.score >= score) continue;
      try { if (statSync(path).size > MAX_BYTES) continue; } catch { continue; }
      best = { path, score };
    }
  };
  walk(root, 0);
  return (best as { path: string } | null)?.path ?? null;
}

export function projectOf(folder: string | null): Project | null {
  if (!folder) return null;
  const hit = byFolder.get(folder);
  if (hit && Date.now() - hit.at < RESCAN_MS) return hit.project;
  const { root, name } = rootOf(folder);
  const project = { name, root, logo: findLogo(root) };
  byFolder.set(folder, { at: Date.now(), project });
  // One logo per name: a project with a logo wins over a namesake without.
  if (project.logo || !byName.get(name)?.logo) byName.set(name, project);
  return project;
}

export const projectLogo = (name: string): string | null => byName.get(name)?.logo ?? null;
