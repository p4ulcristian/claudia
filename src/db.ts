// The app's own store: only what Claude Code's session files don't hold.
// Chats themselves live in ~/.claude/projects (decision 5).
import { Database } from "bun:sqlite";
import { mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { Scene } from "./scene";

const dir = join(process.env.XDG_DATA_HOME ?? join(homedir(), ".local/share"), "claudia");
mkdirSync(dir, { recursive: true });

const db = new Database(join(dir, "app.db"));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS chats (
    session_id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL
  );
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
  settings: db.query<{ model: string | null; effort: string | null }, [string]>("SELECT model, effort FROM chat_settings WHERE session_id = ?"),
  saveSettings: db.query(
    `INSERT INTO chat_settings (session_id, model, effort) VALUES (?, ?, ?)
     ON CONFLICT (session_id) DO UPDATE SET model = excluded.model, effort = excluded.effort`,
  ),
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
  },
  settings: (id: string) => q.settings.get(id) ?? { model: null, effort: null },
  saveSettings: (id: string, model: string | null, effort: string | null) => q.saveSettings.run(id, model, effort),
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
