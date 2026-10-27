// Full-text search over what was said in the chats: an SQLite FTS5 index of the user's and Claude's
// text in the Claude Code session files (tool calls and results left out). Each file is read on from
// where the index left it, so a search only reads what was written since; a fresh index is built in
// the background when the runner starts. The index lives in its own file and can be deleted any time.
import { Database } from "bun:sqlite";
import { homedir } from "node:os";
import { join } from "node:path";
import { statSync } from "node:fs";
import { DATA_DIR } from "./db";
import { commandParts, sentMidTurn, stripReminders, taskNotice } from "./chat";

const PROJECTS = join(process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), ".claude"), "projects");

const db = new Database(join(DATA_DIR, "search.db"));
db.exec("PRAGMA journal_mode = WAL");
db.exec(`CREATE VIRTUAL TABLE IF NOT EXISTS msgs USING fts5(text, session UNINDEXED, uuid UNINDEXED, role UNINDEXED, tokenize = "unicode61 remove_diacritics 2")`);
db.exec("CREATE TABLE IF NOT EXISTS files (session TEXT PRIMARY KEY, offset INTEGER NOT NULL)");
// Raised when said() reads more of the session files: an older index is emptied and built again.
// 2: messages sent mid-turn (queued_command attachments).
const INDEX_VERSION = 2;
if (db.query<{ user_version: number }, []>("PRAGMA user_version").get()!.user_version < INDEX_VERSION) {
  db.exec("DELETE FROM msgs; DELETE FROM files");
  db.exec(`PRAGMA user_version = ${INDEX_VERSION}`);
}
const q = {
  offset: db.query<{ offset: number }, [string]>("SELECT offset FROM files WHERE session = ?"),
  setOffset: db.query("INSERT INTO files (session, offset) VALUES (?, ?) ON CONFLICT (session) DO UPDATE SET offset = excluded.offset"),
  add: db.query("INSERT INTO msgs (text, session, uuid, role) VALUES (?, ?, ?, ?)"),
  drop: db.query("DELETE FROM msgs WHERE session = ?"),
  find: db.query<{ session: string; uuid: string; role: string; snip: string; rank: number }, [string]>(
    `SELECT session, uuid, role, snippet(msgs, 0, char(1), char(2), '…', 16) AS snip, bm25(msgs) AS rank
     FROM msgs WHERE msgs MATCH ? ORDER BY rank LIMIT 3000`,
  ),
};

const fileOf = (dir: string, session: string) => join(dir, `${session}.jsonl`);

// The text a session line adds to the chat as the user or Claude sees it, or null (as loadHistory in
// chat.ts reads it: task notices and command output are notes, not something said, and stay out).
function said(line: string): { uuid: string; role: "user" | "claude"; text: string } | null {
  if (!line.includes('"type":"user"') && !line.includes('"type":"assistant"') && !line.includes('"queued_command"')) return null;
  let e: any;
  try { e = JSON.parse(line); } catch { return null; }
  if (e.isMeta || e.isSidechain || !e.uuid) return null;
  const midTurn = sentMidTurn(e);
  if (midTurn) e = { type: "user", uuid: midTurn, message: { content: e.attachment.prompt } };
  else if (e.type !== "user" && e.type !== "assistant") return null;
  const content = e.message?.content;
  const blocks: any[] = typeof content === "string" ? [{ type: "text", text: content }] : Array.isArray(content) ? content : [];
  let text = blocks.filter((b) => b?.type === "text" && typeof b.text === "string").map((b) => stripReminders(b.text)).filter(Boolean).join("\n");
  if (e.type === "user") {
    if (taskNotice(text)) return null;
    const cmd = commandParts(text);
    if (cmd) text = cmd.command ?? "";
  }
  if (!text.trim()) return null;
  return { uuid: e.uuid, role: e.type === "user" ? "user" : "claude", text };
}

// Brings one session's index up to date: reads the file from where it was left (all of it when it shrank).
const locks = new Map<string, Promise<void>>();
function update(dir: string, session: string): Promise<void> {
  const prev = locks.get(session) ?? Promise.resolve();
  const next = prev.then(async () => {
    const path = fileOf(dir, session);
    let size: number;
    try { size = statSync(path).size; } catch { return; }
    let from = q.offset.get(session)?.offset ?? 0;
    if (size < from) { q.drop.run(session); from = 0; }
    if (size === from) return;
    const bytes = new Uint8Array(await Bun.file(path).slice(from, size).arrayBuffer());
    const end = bytes.lastIndexOf(10) + 1; // only whole lines; a line still being written waits
    if (!end) return;
    const lines = new TextDecoder().decode(bytes.subarray(0, end)).split("\n");
    db.transaction(() => {
      for (const l of lines) {
        const s = said(l);
        if (s) q.add.run(s.text, session, s.uuid, s.role);
      }
      q.setOffset.run(session, from + end);
    })();
  });
  locks.set(session, next.catch(() => {}));
  return next;
}

// At start: every session of the folder, newest first, one at a time between other work.
export async function warm(dir: string, sessions: string[]) {
  for (const s of sessions) {
    await update(dir, s).catch(() => {});
    await Bun.sleep(0);
  }
}

export type Hit = { uuid: string; role: "user" | "claude"; snippet: string };
export type Found = { id: string; hits: Hit[]; count: number; title: boolean };

// Words as prefixes, all of them: "pipe card" finds "pipeline cards".
function ftsQuery(text: string): string | null {
  const words = text.toLowerCase().match(/[\p{L}\p{N}_]+/gu);
  return words?.length ? words.map((w) => `"${w}"*`).join(" ") : null;
}

// chats: the ones the person can see (id → title), searched in their own files first.
export async function search(dir: string, text: string, chats: Map<string, string>): Promise<Found[]> {
  await Promise.all([...chats.keys()].map((id) => update(dir, id).catch(() => {})));
  const fq = ftsQuery(text);
  if (!fq) return [];
  const words = text.toLowerCase().match(/[\p{L}\p{N}_]+/gu) ?? [];
  const byChat = new Map<string, { best: number; hits: Hit[]; count: number }>();
  for (const r of q.find.all(fq)) {
    if (!chats.has(r.session)) continue;
    const c = byChat.get(r.session) ?? { best: r.rank, hits: [], count: 0 };
    c.count++;
    if (c.hits.length < 3) c.hits.push({ uuid: r.uuid, role: r.role as Hit["role"], snippet: r.snip.replace(/\s+/g, " ") });
    byChat.set(r.session, c);
  }
  const titled = (id: string) => words.every((w) => chats.get(id)!.toLowerCase().includes(w));
  for (const id of chats.keys()) if (!byChat.has(id) && titled(id)) byChat.set(id, { best: 0, hits: [], count: 0 });
  return [...byChat.entries()]
    .map(([id, c]) => ({ id, hits: c.hits, count: c.count, title: titled(id), best: c.best }))
    .sort((a, b) => Number(b.title) - Number(a.title) || a.best - b.best)
    .slice(0, 60)
    .map(({ best: _b, ...f }) => f);
}

export const sessionsDir = (work: string) => join(PROJECTS, work.replace(/[^a-zA-Z0-9]/g, "-"));
