import fs from "node:fs/promises";
import path from "node:path";
import { claudeHome } from "./claude-home";
import type { TitleMap } from "./types";

// User-set custom session titles, persisted under ~/.claude. Keyed by
// sessionId so a title survives the session going active → done → active and
// is independent of folder. Absent ⇒ fall back to the derived first-message
// title. Mirrors session-meta.ts / folders.ts.
function storeFile(): string {
  return path.join(claudeHome(), "claudia-session-titles.json");
}

async function readAll(): Promise<TitleMap> {
  try {
    const raw = await fs.readFile(storeFile(), "utf8");
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return {};
    const out: TitleMap = {};
    for (const [id, v] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof v === "string" && v.trim()) out[id] = v;
    }
    return out;
  } catch {
    return {};
  }
}

async function writeAll(map: TitleMap): Promise<void> {
  const file = storeFile();
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, JSON.stringify(map, null, 2), "utf8");
}

/** The whole custom-title map. */
export async function getTitles(): Promise<TitleMap> {
  return readAll();
}

/** Set (or, with an empty title, clear) a session's custom title. Returns the
 * updated map. */
export async function setTitle(sessionId: string, title: string): Promise<TitleMap> {
  const map = await readAll();
  const t = title.trim();
  if (t) map[sessionId] = t;
  else delete map[sessionId];
  await writeAll(map);
  return map;
}
