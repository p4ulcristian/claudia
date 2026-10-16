// The app's own store: only what Claude Code's session files don't hold.
// Chats themselves live in ~/.claude/projects (decision 5).
import { Database } from "bun:sqlite";
import { mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { Scene } from "./scene";
import type { ChangeMeta, FileState } from "./changes";

const dir = join(process.env.XDG_DATA_HOME ?? join(homedir(), ".local/share"), "claudia");
mkdirSync(join(dir, "index"), { recursive: true });
export const DATA_DIR = dir;

const db = new Database(join(dir, "app.db"));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS chats (
    session_id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL
  );
  -- One row per file Claude changed in a turn: the file before the turn and after it.
  CREATE TABLE IF NOT EXISTS changes (
    id INTEGER PRIMARY KEY,
    session_id TEXT NOT NULL,
    turn_uuid TEXT NOT NULL,
    path TEXT NOT NULL,
    before TEXT,
    after TEXT,
    added INTEGER NOT NULL,
    removed INTEGER NOT NULL,
    status TEXT NOT NULL,
    skipped TEXT,
    explanation TEXT,
    created_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS changes_by_chat ON changes (session_id, created_at);
  -- One row per turn that changed the picture; turn_uuid = the user message it answers.
  -- Model and effort picked for a chat; null = the user's Claude Code default.
  CREATE TABLE IF NOT EXISTS chat_settings (
    session_id TEXT PRIMARY KEY,
    model TEXT,
    effort TEXT
  );
  CREATE TABLE IF NOT EXISTS pictures (
    session_id TEXT NOT NULL,
    turn_uuid TEXT NOT NULL,
    scene TEXT NOT NULL,
    changed TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (session_id, turn_uuid)
  );
`);

// Added after the first release: the chat's working folder.
if (!db.query<{ name: string }, []>("PRAGMA table_info(chat_settings)").all().some((c) => c.name === "folder"))
  db.exec("ALTER TABLE chat_settings ADD COLUMN folder TEXT");

const q = {
  addChat: db.query("INSERT OR IGNORE INTO chats (session_id, created_at) VALUES (?, ?)"),
  ours: db.query<{ session_id: string }, []>("SELECT session_id FROM chats"),
  dropChat: db.query("DELETE FROM chats WHERE session_id = ?"),
  dropPictures: db.query("DELETE FROM pictures WHERE session_id = ?"),
  dropSettings: db.query("DELETE FROM chat_settings WHERE session_id = ?"),
  savePicture: db.query(
    `INSERT INTO pictures (session_id, turn_uuid, scene, changed, created_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (session_id, turn_uuid) DO UPDATE SET scene = excluded.scene, changed = excluded.changed`,
  ),
  settings: db.query<{ model: string | null; effort: string | null; folder: string | null }, [string]>("SELECT model, effort, folder FROM chat_settings WHERE session_id = ?"),
  saveSettings: db.query(
    `INSERT INTO chat_settings (session_id, model, effort, folder) VALUES (?, ?, ?, ?)
     ON CONFLICT (session_id) DO UPDATE SET model = excluded.model, effort = excluded.effort, folder = excluded.folder`,
  ),
  addChange: db.query<{ id: number }, [string, string, string, string | null, string | null, number, number, string, string | null, number]>(
    `INSERT INTO changes (session_id, turn_uuid, path, before, after, added, removed, status, skipped, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`,
  ),
  explainChange: db.query("UPDATE changes SET explanation = ? WHERE id = ?"),
  changes: db.query<any, [string]>(
    "SELECT id, turn_uuid AS turn, path, added, removed, status, skipped, explanation FROM changes WHERE session_id = ? ORDER BY created_at, id",
  ),
  change: db.query<{ path: string; before: string | null; after: string | null; skipped: string | null }, [number, string]>(
    "SELECT path, before, after, skipped FROM changes WHERE id = ? AND session_id = ?",
  ),
  dropChanges: db.query("DELETE FROM changes WHERE session_id = ?"),
  pictures: db.query<{ turn_uuid: string; scene: string; changed: string; created_at: number }, [string]>(
    "SELECT turn_uuid, scene, changed, created_at FROM pictures WHERE session_id = ? ORDER BY created_at",
  ),
};

export const store = {
  addChat: (id: string) => q.addChat.run(id, Date.now()),
  ourChats: () => new Set(q.ours.all().map((r) => r.session_id)),
  deleteChat(id: string) {
    q.dropChat.run(id);
    q.dropPictures.run(id);
    q.dropSettings.run(id);
    q.dropChanges.run(id);
  },
  settings: (id: string) => q.settings.get(id) ?? { model: null, effort: null, folder: null },
  saveSettings: (id: string, s: { model: string | null; effort: string | null; folder: string | null }) => q.saveSettings.run(id, s.model, s.effort, s.folder),
  addChange(id: string, turn: string, path: string, before: FileState, after: FileState, counts: { added: number; removed: number }): ChangeMeta {
    const status = before.text === null && !before.skipped ? "added" : after.text === null && !after.skipped ? "deleted" : "modified";
    const skipped = before.skipped ?? after.skipped ?? null;
    const row = q.addChange.get(id, turn, path, before.text, after.text, counts.added, counts.removed, status, skipped, Date.now())!;
    return { id: row.id, turn, path, ...counts, status, skipped: skipped ?? undefined, explanation: null };
  },
  explainChange: (changeId: number, line: string) => q.explainChange.run(line, changeId),
  changes: (id: string): ChangeMeta[] => q.changes.all(id).map((r) => ({ ...r, skipped: r.skipped ?? undefined })),
  change: (id: string, changeId: number) => q.change.get(changeId, id),
  savePicture: (id: string, turn: string, scene: Scene, changed: string[]) =>
    q.savePicture.run(id, turn, JSON.stringify(scene), JSON.stringify(changed), Date.now()),
  pictures: (id: string) =>
    q.pictures.all(id).map((r) => ({
      turn: r.turn_uuid,
      scene: JSON.parse(r.scene) as Scene,
      changed: JSON.parse(r.changed) as string[],
      at: r.created_at,
    })),
};
