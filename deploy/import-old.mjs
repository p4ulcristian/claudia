// Imports the TypeScript Claudia's chat list into this Claudia's store, for one profile:
//   node deploy/import-old.mjs <old app.db> <new RUNNER_DATA folder> <chats folder (CLAUDIA_CWD)>
// Stop the profile's runner first (it keeps rows in memory), start it after.
//
// The chats themselves are Claude Code's session files, which both read; what moves is the old
// store's part: which sessions are Claudia chats, archived or not, folder, colour, and the newest
// picture (only the blocks this version draws: graph, html and its five forms). A chat already in
// the new store is left as it is, so running this again only adds what is new.
import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { listSessions } from "@anthropic-ai/claude-agent-sdk";

const [oldPath, dataDir, cwd] = process.argv.slice(2);
if (!oldPath || !dataDir || !cwd) {
  console.error("usage: node deploy/import-old.mjs <old app.db> <RUNNER_DATA> <CLAUDIA_CWD>");
  process.exit(1);
}

const COLORS = new Set(["burgundy", "red", "orange", "amber", "yellow", "lime", "green", "teal", "cyan",
  "sky", "blue", "indigo", "violet", "purple", "magenta", "pink", "brown"]);
const FORMS = new Set(["pipeline", "timeline", "compare", "chart", "stats"]);

const old = new DatabaseSync(oldPath, { readOnly: true });
const ours = new Set(old.prepare("SELECT session_id FROM chats").all().map((r) => r.session_id));
const archived = new Set(old.prepare("SELECT session_id FROM archived").all().map((r) => r.session_id));
const settings = new Map(old.prepare("SELECT session_id, folder, color FROM chat_settings").all().map((r) => [r.session_id, r]));
const newestScene = old.prepare("SELECT scene FROM pictures WHERE session_id = ? ORDER BY created_at DESC LIMIT 1");

// Keeps what this version can draw; a picture left with no block is dropped.
function scene(id) {
  const r = newestScene.get(id);
  if (!r) return null;
  const s = JSON.parse(r.scene);
  const blocks = (s.blocks ?? []).filter((b) =>
    b.kind === "graph" || (b.kind === "html" && b.html) || (b.kind === "form" && FORMS.has(b.form)));
  return blocks.length ? JSON.stringify({ title: s.title ?? "", blocks }) : null;
}

mkdirSync(dataDir, { recursive: true });
const db = new DatabaseSync(join(dataDir, "claudia.db"));
db.exec(`PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', folder TEXT, color TEXT,
    archived INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, at INTEGER NOT NULL,
    ended INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0,
    preview TEXT NOT NULL DEFAULT '', scene TEXT);`);
const insert = db.prepare(`INSERT OR IGNORE INTO chats
  (id, title, folder, color, archived, created, at, ended, seen, preview, scene)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?)`);

const sessions = await listSessions({ dir: cwd, includeWorktrees: false });
let added = 0, shown = 0;
for (const s of sessions) {
  if (!ours.has(s.sessionId)) continue;
  const set = settings.get(s.sessionId) ?? {};
  const title = (s.customTitle || s.summary || s.firstPrompt || "Chat").replace(/\s+/g, " ").slice(0, 120);
  const at = s.lastModified ?? Date.now();
  const isArchived = archived.has(s.sessionId) ? 1 : 0;
  // Seen at its last activity: an imported chat doesn't light up as new.
  const r = insert.run(s.sessionId, title, set.folder ?? null, COLORS.has(set.color) ? set.color : null,
    isArchived, s.createdAt ?? at, at, at, at, scene(s.sessionId));
  if (r.changes) { added++; if (!isArchived) shown++; }
}
console.log(`imported ${added} chats (${shown} not archived) from ${oldPath}`);
