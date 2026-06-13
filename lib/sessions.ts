import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import readline from "node:readline";
import { sessionDir } from "./claude-home";
import type { ClaudeEvent, SessionSummary, TranscriptDelta } from "./types";

function parseLine(line: string): ClaudeEvent | null {
  try {
    return JSON.parse(line) as ClaudeEvent;
  } catch {
    return null;
  }
}

// Structural / meta line types that carry no conversation content.
const SKIP_TYPES = new Set([
  "mode",
  "permission-mode",
  "file-history-snapshot",
  "summary",
  "attachment",
]);

/**
 * Scan the first lines of a transcript for the first human user message and
 * return a short title. Skips tool-result/meta lines whose content is a vector.
 */
async function firstUserText(file: string): Promise<string | null> {
  const rl = readline.createInterface({
    input: fs.createReadStream(file, { encoding: "utf8" }),
    crlfDelay: Infinity,
  });
  let count = 0;
  try {
    for await (const line of rl) {
      if (count++ >= 80) break;
      const evt = parseLine(line);
      if (!evt) continue;
      const content = evt.message?.content;
      let text: string | null = null;
      if (evt.type === "user" && typeof content === "string") text = content;
      else if (evt.type === "user" && typeof evt.content === "string")
        text = evt.content as string;
      if (text) {
        const t = text.trim();
        if (t && !t.startsWith("<")) {
          return t.length > 80 ? `${t.slice(0, 80)}…` : t;
        }
      }
    }
  } finally {
    rl.close();
  }
  return null;
}

/** Session summaries for a folder, newest first. */
export async function listSessions(folder: string): Promise<SessionSummary[]> {
  const dir = sessionDir(folder);
  let entries: string[];
  try {
    entries = await fsp.readdir(dir);
  } catch {
    return [];
  }
  const files = entries.filter((n) => n.endsWith(".jsonl"));
  const summaries = await Promise.all(
    files.map(async (name): Promise<SessionSummary> => {
      const full = path.join(dir, name);
      const stat = await fsp.stat(full);
      const title = (await firstUserText(full)) ?? "(no messages)";
      return {
        sessionId: name.replace(/\.jsonl$/, ""),
        title,
        modified: stat.mtimeMs,
        size: stat.size,
      };
    }),
  );
  return summaries.sort((a, b) => b.modified - a.modified);
}

/** Read a transcript file (optionally from a byte offset), parsed and filtered. */
async function readEvents(file: string, start = 0): Promise<ClaudeEvent[]> {
  const rl = readline.createInterface({
    input: fs.createReadStream(file, { encoding: "utf8", start }),
    crlfDelay: Infinity,
  });
  const events: ClaudeEvent[] = [];
  for await (const line of rl) {
    const evt = parseLine(line);
    if (!evt) continue;
    if (evt.type && SKIP_TYPES.has(evt.type)) continue;
    events.push(evt);
  }
  return events;
}

/**
 * Parse a session transcript into a list of raw events for the renderer.
 * Drops unparseable and structural-only lines.
 */
export async function loadSession(
  folder: string,
  sessionId: string,
): Promise<ClaudeEvent[]> {
  const file = path.join(sessionDir(folder), `${sessionId}.jsonl`);
  try {
    await fsp.access(file);
  } catch {
    return [];
  }
  return readEvents(file);
}

/**
 * Load a transcript incrementally. Given the byte offset (`since`) the caller
 * already has, return only the events appended past it. `.jsonl` is append-only
 * and every complete line ends in "\n", so a quiescent file size is always a
 * clean line boundary. `reset` signals the caller must drop its cache: the file
 * is missing, was loaded fresh, or shrank/was rewritten (since > size).
 */
export async function loadSessionDelta(
  folder: string,
  sessionId: string,
  since = 0,
): Promise<TranscriptDelta> {
  const file = path.join(sessionDir(folder), `${sessionId}.jsonl`);
  let stat;
  try {
    stat = await fsp.stat(file);
  } catch {
    return { events: [], size: 0, modified: 0, reset: true };
  }
  const size = stat.size;
  const modified = stat.mtimeMs;
  const reset = since <= 0 || since > size;
  if (!reset && since === size) return { events: [], size, modified, reset: false };
  const events = await readEvents(file, reset ? 0 : since);
  return { events, size, modified, reset };
}
