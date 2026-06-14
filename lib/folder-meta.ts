import fs from "node:fs/promises";
import path from "node:path";
import { claudeHome } from "./claude-home";
import { FOLDER_COLORS, type FolderColor, type FolderMetaMap } from "./types";

// Per-folder presentation (theme color), persisted under ~/.claude. Keyed by
// the same normalised folder path used in claudia-folders.json. Mirrors
// folders.ts / session-meta.ts.
function storeFile(): string {
  return path.join(claudeHome(), "claudia-folder-meta.json");
}

function isColor(v: unknown): v is FolderColor {
  return typeof v === "string" && (FOLDER_COLORS as readonly string[]).includes(v);
}

async function readAll(): Promise<FolderMetaMap> {
  try {
    const raw = await fs.readFile(storeFile(), "utf8");
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object") return {};
    const out: FolderMetaMap = {};
    for (const [folder, v] of Object.entries(parsed as Record<string, unknown>)) {
      const color = (v as { color?: unknown })?.color;
      if (isColor(color)) out[folder] = { color };
    }
    return out;
  } catch {
    return {};
  }
}

async function writeAll(map: FolderMetaMap): Promise<void> {
  const file = storeFile();
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, JSON.stringify(map, null, 2), "utf8");
}

/** The whole folder→meta map. */
export async function getFolderMeta(): Promise<FolderMetaMap> {
  return readAll();
}

/** Set a folder's theme color, or clear it when `color` is null/unknown.
 * Returns the updated map. */
export async function setFolderColor(
  rawPath: string,
  color: string | null,
): Promise<FolderMetaMap> {
  const folder = String(rawPath ?? "").trim().replace(/\/+$/, "");
  if (!folder) return readAll();
  const map = await readAll();
  if (isColor(color)) map[folder] = { color };
  else delete map[folder];
  await writeAll(map);
  return map;
}
