import fs from "node:fs/promises";
import path from "node:path";
import { claudeHome } from "./claude-home";
import type { ActiveMap } from "./types";

// The "active" session set, persisted under ~/.claude. A session is active
// (shown on the home screen) when present here; absent ⇒ done (the default).
// Keyed by sessionId so it's consistent across devices. Mirrors folders.ts.
function storeFile(): string {
  return path.join(claudeHome(), "claudia-session-meta.json");
}

async function readAll(): Promise<ActiveMap> {
  try {
    const raw = await fs.readFile(storeFile(), "utf8");
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return {};
    // Keep only well-formed active entries (drops the old {done} shape).
    const out: ActiveMap = {};
    for (const [id, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (v && typeof v === "object" && typeof (v as { folder?: unknown }).folder === "string") {
        out[id] = v as ActiveMap[string];
      }
    }
    return out;
  } catch {
    return {};
  }
}

async function writeAll(map: ActiveMap): Promise<void> {
  const file = storeFile();
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, JSON.stringify(map, null, 2), "utf8");
}

/** The whole active-session map. */
export async function getActive(): Promise<ActiveMap> {
  return readAll();
}

/** Mark a session active (upsert). Returns the updated map. */
export async function setActive(
  sessionId: string,
  folder: string,
  title: string,
  at: number,
): Promise<ActiveMap> {
  const map = await readAll();
  map[sessionId] = { folder, title, lastActiveAt: at };
  await writeAll(map);
  return map;
}

/** Mark a session done (remove from the active set). Returns the updated map. */
export async function clearActive(sessionId: string): Promise<ActiveMap> {
  const map = await readAll();
  if (sessionId in map) {
    delete map[sessionId];
    await writeAll(map);
  }
  return map;
}
