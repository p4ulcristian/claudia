import fs from "node:fs/promises";
import path from "node:path";
import { claudeHome } from "./claude-home";
import type { FolderPath } from "./types";

// Persisted as JSON under ~/.claude so the list survives restarts. Single-user
// box, so it's just a flat array of paths (the original keyed by user-id for a
// multi-tenant server; claudia is intentionally simpler).
function storeFile(): string {
  return path.join(claudeHome(), "claudia-folders.json");
}

async function readAll(): Promise<FolderPath[]> {
  try {
    const raw = await fs.readFile(storeFile(), "utf8");
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((p) => typeof p === "string") : [];
  } catch {
    return [];
  }
}

async function writeAll(list: FolderPath[]): Promise<void> {
  const file = storeFile();
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, JSON.stringify(list, null, 2), "utf8");
}

/** Folder paths in insertion order. */
export async function listFolders(): Promise<FolderPath[]> {
  return readAll();
}

/** Normalise (trim, strip trailing slashes) and add a folder. Returns the new list. */
export async function addFolder(rawPath: string): Promise<FolderPath[]> {
  const p = String(rawPath ?? "").trim().replace(/\/+$/, "");
  const cur = await readAll();
  if (!p || cur.includes(p)) return cur;
  const next = [...cur, p];
  await writeAll(next);
  return next;
}

/** Remove a folder path. Returns the new list. */
export async function removeFolder(rawPath: string): Promise<FolderPath[]> {
  const p = String(rawPath ?? "");
  const next = (await readAll()).filter((x) => x !== p);
  await writeAll(next);
  return next;
}
