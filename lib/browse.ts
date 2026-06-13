import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { sessionDir } from "./claude-home";
import type { BrowseResult, DirEntry } from "./types";

/** Resolve to an absolute, canonical path. Blank/nil defaults to the home dir. */
function normalize(p?: string | null): string {
  const trimmed = p?.trim();
  const base = trimmed ? trimmed : os.homedir();
  try {
    return path.resolve(base);
  } catch {
    return os.homedir();
  }
}

/** True if this folder already has Claude session transcripts on disk. */
async function hasSessions(dir: string): Promise<boolean> {
  try {
    const entries = await fs.readdir(sessionDir(dir));
    return entries.some((n) => n.endsWith(".jsonl"));
  } catch {
    return false;
  }
}

/**
 * List the immediate sub-directories of `path` (default: home). Hidden
 * (dot-prefixed) dirs are skipped to keep the picker tidy.
 */
export async function listDir(p?: string | null): Promise<BrowseResult> {
  const dir = normalize(p);
  const parent = path.dirname(dir);

  let dirs: DirEntry[] = [];
  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    const subdirs = entries
      .filter((e) => e.isDirectory() && !e.name.startsWith("."))
      .map((e) => path.join(dir, e.name))
      .sort((a, b) => path.basename(a).toLowerCase().localeCompare(path.basename(b).toLowerCase()));

    dirs = await Promise.all(
      subdirs.map(async (full): Promise<DirEntry> => ({
        name: path.basename(full),
        path: full,
        hasSessions: await hasSessions(full),
      })),
    );
  } catch {
    dirs = [];
  }

  return {
    path: dir,
    // At filesystem root, dirname(dir) === dir; surface null so the UI hides "up".
    parent: parent === dir ? null : parent,
    dirs,
  };
}
