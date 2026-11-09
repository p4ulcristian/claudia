// Moves one profile's TypeScript Claudia store (app.db) into this Claudia's (claudia.db):
//   node deploy/import-old.mjs <old app.db> <RUNNER_DATA folder> <chats folder (CLAUDIA_CWD)>
// Stop the profile's runner first (it keeps rows in memory), start it after.
//
// The chats themselves are Claude Code's session files, which both read; what moves is everything
// the old store kept beside them: which sessions are Claudia chats (archived or not, folder, colour,
// the colour a passive chat had), every turn's picture (the picture history), every turn's file
// changes, the templates, each chat's model and effort, how
// its last turn ended and when it was last seen. Running it again only adds what is missing: rows
// already here are kept as they are, except a chat's live picture, which follows the old store's
// newest picture unless this Claudia drew a newer one.
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
  "sky", "blue", "indigo", "violet", "purple", "magenta", "pink", "brown", "ash"]);

const old = new DatabaseSync(oldPath, { readOnly: true });
const has = (t) => !!old.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").get(t);
const all = (t, sql) => (has(t) ? old.prepare(sql).all() : []);

const ours = new Set(all("chats", "SELECT session_id FROM chats").map((r) => r.session_id));
const archived = new Set(all("archived", "SELECT session_id FROM archived").map((r) => r.session_id));
const settings = new Map(all("chat_settings", "SELECT * FROM chat_settings").map((r) => [r.session_id, r]));
const lastTurns = new Map(all("last_turns", "SELECT * FROM last_turns").map((r) => [r.session_id, r]));
const seen = new Map(all("seen", "SELECT * FROM seen").map((r) => [r.session_id, r.at]));

mkdirSync(dataDir, { recursive: true });
const db = new DatabaseSync(join(dataDir, "claudia.db"));
// The runner makes these on its start; made here too so a store that never ran can be filled.
db.exec(`PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', folder TEXT, color TEXT,
    archived INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, at INTEGER NOT NULL,
    ended INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0,
    preview TEXT NOT NULL DEFAULT '', scene TEXT, prev_color TEXT, last_turn TEXT,
    stopped INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0, reply TEXT NOT NULL DEFAULT '');
  CREATE TABLE IF NOT EXISTS pictures (chat TEXT NOT NULL, turn TEXT NOT NULL, scene TEXT NOT NULL,
    at INTEGER NOT NULL, PRIMARY KEY (chat, turn));
  CREATE TABLE IF NOT EXISTS kv (chat TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (chat, key));
  CREATE TABLE IF NOT EXISTS changes (id INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT NOT NULL, turn TEXT NOT NULL,
    path TEXT NOT NULL, before TEXT, after TEXT, added INTEGER NOT NULL, removed INTEGER NOT NULL,
    status TEXT NOT NULL, skipped TEXT, at INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS templates (name TEXT PRIMARY KEY COLLATE NOCASE, html TEXT NOT NULL,
    parts TEXT NOT NULL DEFAULT '{}', description TEXT NOT NULL DEFAULT '', at INTEGER NOT NULL);`);

const n = { chats: 0, pictures: 0, changes: 0, templates: 0, settings: 0, scenes: 0 };
db.exec("BEGIN");

// Chats: the Claude Code sessions the old store called its own.
const insertChat = db.prepare(`INSERT OR IGNORE INTO chats
  (id, title, folder, color, archived, created, at, ended, seen, preview, scene, prev_color, last_turn, stopped, failed, reply)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)`);
for (const s of await listSessions({ dir: cwd, includeWorktrees: false })) {
  if (!ours.has(s.sessionId)) continue;
  const set = settings.get(s.sessionId) ?? {};
  const lt = lastTurns.get(s.sessionId);
  const title = (s.customTitle || s.summary || s.firstPrompt || "Chat").replace(/\s+/g, " ").slice(0, 120);
  const at = s.lastModified ?? Date.now();
  const r = insertChat.run(s.sessionId, title, set.folder ?? null, COLORS.has(set.color) ? set.color : null,
    archived.has(s.sessionId) ? 1 : 0, s.createdAt ?? at, at, lt?.at ?? at, seen.get(s.sessionId) ?? at,
    lt?.reply ?? "", COLORS.has(set.prev_color) ? set.prev_color : null, lt?.turn ?? null,
    lt?.stopped ? 1 : 0, lt?.failed ? 1 : 0, lt?.reply ?? "");
  n.chats += r.changes;
}
const here = new Set(db.prepare("SELECT id FROM chats").all().map((r) => r.id));

// Every turn's picture: the history the stepper walks.
const insertPicture = db.prepare("INSERT OR IGNORE INTO pictures (chat, turn, scene, at) VALUES (?, ?, ?, ?)");
for (const p of all("pictures", "SELECT session_id, turn_uuid, scene, created_at FROM pictures ORDER BY created_at")) {
  if (!here.has(p.session_id)) continue;
  n.pictures += insertPicture.run(p.session_id, p.turn_uuid, p.scene, p.created_at).changes;
}

// The live picture: the newest one, whole (a first import kept only the blocks it could draw then),
// unless this Claudia has drawn a newer picture for the chat since.
const newestOld = db.prepare("SELECT scene, at FROM pictures WHERE chat = ? ORDER BY at DESC LIMIT 1");
const setScene = db.prepare("UPDATE chats SET scene = ? WHERE id = ?");
const oldNewestOf = has("pictures") ? old.prepare("SELECT max(created_at) AS at FROM pictures WHERE session_id = ?") : null;
for (const id of here) {
  const p = newestOld.get(id);
  if (!p) continue;
  const oldNewest = has("pictures") ? oldNewestOf.get(id)?.at : null;
  if (oldNewest && p.at <= oldNewest) n.scenes += setScene.run(p.scene, id).changes;
}

// Every turn's file changes (the changes pill), once per chat, turn and file.
const haveChange = db.prepare("SELECT 1 FROM changes WHERE chat = ? AND turn = ? AND path = ?");
const insertChange = db.prepare(`INSERT INTO changes (chat, turn, path, before, after, added, removed, status, skipped, at)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
for (const c of all("changes", "SELECT * FROM changes ORDER BY id")) {
  if (!here.has(c.session_id) || haveChange.get(c.session_id, c.turn_uuid, c.path)) continue;
  insertChange.run(c.session_id, c.turn_uuid, c.path, c.before, c.after, c.added, c.removed, c.status, c.skipped, c.created_at);
  n.changes++;
}

// Templates (by name).
const insertTemplate = db.prepare("INSERT OR IGNORE INTO templates (name, html, parts, description, at) VALUES (?, ?, ?, ?, ?)");
for (const t of all("templates", "SELECT * FROM templates")) {
  n.templates += insertTemplate.run(t.name, t.html, t.parts || "{}", t.description || "", t.updated_at).changes;
}

// Each chat's model and effort.
const haveKv = db.prepare("SELECT 1 FROM kv WHERE chat = ? AND key = 'settings'");
const insertKv = db.prepare("INSERT INTO kv (chat, key, value) VALUES (?, 'settings', ?)");
for (const [id, s] of settings) {
  if (!here.has(id)) continue;
  if ((s.model || s.effort) && !haveKv.get(id)) n.settings += insertKv.run(id, JSON.stringify({ model: s.model ?? null, effort: s.effort ?? null })).changes;
}

db.exec("COMMIT");
console.log(`from ${oldPath}: ${n.chats} chats, ${n.pictures} pictures, ${n.scenes} live pictures, ${n.changes} changes, ${n.templates} templates, ${n.settings} chat settings`);
