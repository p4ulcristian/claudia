// A chat's history in pages: an open sends the newest page, and the page asks for older ones as the
// user scrolls up. The built history is kept per chat while its session file stays the same (size and
// time), so the pages after the first don't read and parse the file again.
import { stat } from "node:fs/promises";
import { loadHistory, sessionFile, type Item } from "./chat";
import { mergedSends } from "./timeline";

// About this many items make a page; a page starts at a user message, so a turn is never split.
export const PAGE = 150;
const KEEP = 6;

type Built = { stamp: string; items: Item[]; merged: Set<string> };
const built = new Map<string, Built>();

export async function history(id: string): Promise<{ items: Item[]; merged: Set<string> }> {
  const st = await stat(sessionFile(id)).catch(() => null);
  const stamp = st ? `${st.size}:${st.mtimeMs}` : "";
  const have = built.get(id);
  if (have && stamp && have.stamp === stamp) {
    built.delete(id);
    built.set(id, have);
    return have;
  }
  const [items, merged] = await Promise.all([loadHistory(id).catch(() => [] as Item[]), mergedSends(id).catch(() => new Set<string>())]);
  if (stamp) {
    built.delete(id);
    built.set(id, { stamp, items, merged });
    for (const k of built.keys()) if (built.size > KEEP) built.delete(k);
  }
  return { items, merged };
}

// The same key the page tells items apart by (web/app.tsx itemKey).
export const itemKey = (it: Item) => (it.k === "user" ? `u:${it.uuid}` : `${it.k}:${it.id}`);

// The message a search hit points at: a user message by its uuid, Claude's text by its message's uuid.
const isAt = (it: Item, uuid: string) => (it.k === "user" ? it.uuid === uuid : it.k === "text" && it.id.split(":")[0] === uuid);

// Where the page of items before `end` starts: PAGE items back, further back to reach `until` (a search
// hit's message) when it lies earlier, then back to the user message that began that turn.
export function pageStart(items: Item[], end: number, until?: string): number {
  let i = Math.max(0, end - PAGE);
  if (until) {
    const at = items.findIndex((it) => isAt(it, until));
    if (at >= 0 && at < i) i = at;
  }
  while (i > 0 && items[i]!.k !== "user") i--;
  return i;
}

// The page before the item with this key, and whether there are more before it.
export function olderPage(items: Item[], before: string, until?: string): { items: Item[]; more: boolean } {
  const end = items.findIndex((it) => itemKey(it) === before);
  if (end <= 0) return { items: [], more: false };
  const start = pageStart(items, end, until);
  return { items: items.slice(start, end), more: start > 0 };
}
