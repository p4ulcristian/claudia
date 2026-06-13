import fs from "node:fs/promises";
import path from "node:path";
import { claudeHome } from "./claude-home";
import type { SessionMeta } from "./types";

// Per-session triage state (currently just `done`), persisted under ~/.claude
// keyed by sessionId so marks are consistent across devices. Mirrors folders.ts.
type MetaMap = Record<string, SessionMeta>;

function storeFile(): string {
  return path.join(claudeHome(), "claudia-session-meta.json");
}

async function readAll(): Promise<MetaMap> {
  try {
    const raw = await fs.readFile(storeFile(), "utf8");
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? (parsed as MetaMap) : {};
  } catch {
    return {};
  }
}

async function writeAll(map: MetaMap): Promise<void> {
  const file = storeFile();
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, JSON.stringify(map, null, 2), "utf8");
}

/** The whole sessionId → meta map. */
export async function getAllMeta(): Promise<MetaMap> {
  return readAll();
}

/** Set (or clear) the done flag for a session. Returns the updated map. */
export async function setDone(sessionId: string, done: boolean): Promise<MetaMap> {
  const map = await readAll();
  if (done) map[sessionId] = { ...map[sessionId], done: true };
  else delete map[sessionId]; // `done` is the only field — drop the entry entirely
  await writeAll(map);
  return map;
}
