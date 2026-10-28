// The app's own store: only what Claude Code's session files don't hold.
// Chats themselves live in ~/.claude/projects (decision 5).
import { Database } from "bun:sqlite";
import { mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { Scene } from "./scene";
import type { ChangeMeta, FileState } from "./changes";
import type { Settings } from "./chat";

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
  -- Chats put away: hidden from the list and the rail, everything kept.
  CREATE TABLE IF NOT EXISTS archived (
    session_id TEXT PRIMARY KEY,
    at INTEGER NOT NULL
  );
  -- This profile's preferences (each profile's runner has its own store).
  CREATE TABLE IF NOT EXISTS prefs (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS pictures (
    session_id TEXT NOT NULL,
    turn_uuid TEXT NOT NULL,
    scene TEXT NOT NULL,
    changed TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (session_id, turn_uuid)
  );
  -- Freeform blocks saved under a name, for Claude to draw again (and change when asked).
  CREATE TABLE IF NOT EXISTS templates (
    name TEXT PRIMARY KEY,
    html TEXT NOT NULL,
    parts TEXT NOT NULL,
    description TEXT NOT NULL,
    updated_at INTEGER NOT NULL
  );
`);

// Added after the first release: the chat's working folder.
if (!db.query<{ name: string }, []>("PRAGMA table_info(chat_settings)").all().some((c) => c.name === "folder"))
  db.exec("ALTER TABLE chat_settings ADD COLUMN folder TEXT");
// And its colour from the palette, to tell chats apart at a glance.
if (!db.query<{ name: string }, []>("PRAGMA table_info(chat_settings)").all().some((c) => c.name === "color"))
  db.exec("ALTER TABLE chat_settings ADD COLUMN color TEXT");
// The chat's form choices from the row over the picture: JSON { <form>: "required" | "off" }.
if (!db.query<{ name: string }, []>("PRAGMA table_info(chat_settings)").all().some((c) => c.name === "forms"))
  db.exec("ALTER TABLE chat_settings ADD COLUMN forms TEXT");
// The colour a chat had before it was made passive, given back when it wakes.
if (!db.query<{ name: string }, []>("PRAGMA table_info(chat_settings)").all().some((c) => c.name === "prev_color"))
  db.exec("ALTER TABLE chat_settings ADD COLUMN prev_color TEXT");
// Columns for the chat's picture: 0 = Claude's layout, 1–3 = that many equal columns.
if (!db.query<{ name: string }, []>("PRAGMA table_info(chat_settings)").all().some((c) => c.name === "cols"))
  db.exec("ALTER TABLE chat_settings ADD COLUMN cols INTEGER");
// The one-word rail names Haiku picked (src/words.ts) are gone: the rail shows the title.
db.exec("DROP TABLE IF EXISTS chat_words");

// chatColors: "each" = every new chat gets a colour of its own; "none" = new chats keep the default look.
export type Template = { name: string; html: string; parts: Record<string, string>; description: string; updatedAt: number };

// hiddenForms: built-in forms the user deleted in the Forms window (Claude no longer gets them).
export type Prefs = { chatColors: "each" | "none"; hiddenForms: string[] };
const PREF_DEFAULTS: Prefs = { chatColors: "each", hiddenForms: [] };

const q = {
  prefs: db.query<{ key: string; value: string }, []>("SELECT key, value FROM prefs"),
  setPref: db.query("INSERT INTO prefs (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value"),
  addChat: db.query("INSERT OR IGNORE INTO chats (session_id, created_at) VALUES (?, ?)"),
  ours: db.query<{ session_id: string }, []>("SELECT session_id FROM chats"),
  archive: db.query("INSERT OR IGNORE INTO archived (session_id, at) VALUES (?, ?)"),
  unarchive: db.query("DELETE FROM archived WHERE session_id = ?"),
  archived: db.query<{ session_id: string }, []>("SELECT session_id FROM archived"),
  savePicture: db.query(
    `INSERT INTO pictures (session_id, turn_uuid, scene, changed, created_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (session_id, turn_uuid) DO UPDATE SET scene = excluded.scene, changed = excluded.changed`,
  ),
  settings: db.query<Omit<Settings, "forms"> & { forms: string | null }, [string]>("SELECT model, effort, folder, color, forms, cols, prev_color AS prevColor FROM chat_settings WHERE session_id = ?"),
  saveSettings: db.query(
    `INSERT INTO chat_settings (session_id, model, effort, folder, color, forms, cols, prev_color) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (session_id) DO UPDATE SET model = excluded.model, effort = excluded.effort, folder = excluded.folder, color = excluded.color, forms = excluded.forms, cols = excluded.cols, prev_color = excluded.prev_color`,
  ),
  colors: db.query<{ session_id: string; color: string }, []>("SELECT session_id, color FROM chat_settings WHERE color IS NOT NULL"),
  setColor: db.query(
    `INSERT INTO chat_settings (session_id, color) VALUES (?, ?)
     ON CONFLICT (session_id) DO UPDATE SET color = excluded.color`,
  ),
  addChange: db.query<{ id: number }, [string, string, string, string | null, string | null, number, number, string, string | null, number]>(
    `INSERT INTO changes (session_id, turn_uuid, path, before, after, added, removed, status, skipped, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id`,
  ),
  changes: db.query<any, [string]>(
    "SELECT id, turn_uuid AS turn, path, added, removed, status, skipped FROM changes WHERE session_id = ? ORDER BY created_at, id",
  ),
  changeRows: db.query<{ turn_uuid: string; path: string; before: string | null; after: string | null; added: number; removed: number; status: string; skipped: string | null; created_at: number }, [string]>(
    "SELECT turn_uuid, path, before, after, added, removed, status, skipped, created_at FROM changes WHERE session_id = ? ORDER BY created_at, id",
  ),
  change: db.query<{ path: string; before: string | null; after: string | null; skipped: string | null }, [number, string]>(
    "SELECT path, before, after, skipped FROM changes WHERE id = ? AND session_id = ?",
  ),
  templates: db.query<{ name: string; html: string; parts: string; description: string; updated_at: number }, []>("SELECT * FROM templates ORDER BY name COLLATE NOCASE"),
  template: db.query<{ name: string; html: string; parts: string; description: string; updated_at: number }, [string]>("SELECT * FROM templates WHERE name = ? COLLATE NOCASE"),
  saveTemplate: db.query(
    "INSERT INTO templates (name, html, parts, description, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (name) DO UPDATE SET html = excluded.html, parts = excluded.parts, description = excluded.description, updated_at = excluded.updated_at",
  ),
  deleteTemplate: db.query("DELETE FROM templates WHERE name = ? COLLATE NOCASE"),
  pictures: db.query<{ turn_uuid: string; scene: string; changed: string; created_at: number }, [string]>(
    "SELECT turn_uuid, scene, changed, created_at FROM pictures WHERE session_id = ? ORDER BY created_at",
  ),
};

export const store = {
  prefs(): Prefs {
    const out: Prefs = { ...PREF_DEFAULTS };
    for (const r of q.prefs.all()) {
      if (r.key === "chatColors" && (r.value === "each" || r.value === "none")) out.chatColors = r.value;
      if (r.key === "hiddenForms") try { out.hiddenForms = (JSON.parse(r.value) as unknown[]).filter((x): x is string => typeof x === "string"); } catch {}
    }
    return out;
  },
  setPrefs(next: Partial<Prefs>) {
    if (next.chatColors === "each" || next.chatColors === "none") q.setPref.run("chatColors", next.chatColors);
    if (Array.isArray(next.hiddenForms)) q.setPref.run("hiddenForms", JSON.stringify(next.hiddenForms.filter((x) => typeof x === "string")));
    return store.prefs();
  },
  addChat: (id: string) => q.addChat.run(id, Date.now()),
  ourChats: () => new Set(q.ours.all().map((r) => r.session_id)),
  archive: (id: string) => q.archive.run(id, Date.now()),
  unarchive: (id: string) => q.unarchive.run(id),
  archived: () => new Set(q.archived.all().map((r) => r.session_id)),
  settings: (id: string): Settings => {
    const r = q.settings.get(id);
    if (!r) return { model: null, effort: null, folder: null, color: null, forms: {}, cols: 0, prevColor: null };
    let forms: Settings["forms"] = {};
    try { forms = r.forms ? JSON.parse(r.forms) : {}; } catch {}
    // Before "required", green was saved as "use" (then: only the green kinds).
    for (const f of Object.keys(forms)) if ((forms[f] as string) === "use") forms[f] = "required";
    return { ...r, forms, cols: r.cols ?? 0 };
  },
  saveSettings: (id: string, s: Settings) => q.saveSettings.run(id, s.model, s.effort, s.folder, s.color, JSON.stringify(s.forms ?? {}), s.cols ?? 0, s.prevColor ?? null),
  colors: () => new Map(q.colors.all().map((r) => [r.session_id, r.color])),
  setColor: (id: string, color: string) => q.setColor.run(id, color),
  addChange(id: string, turn: string, path: string, before: FileState, after: FileState, counts: { added: number; removed: number }): ChangeMeta {
    const status = before.text === null && !before.skipped ? "added" : after.text === null && !after.skipped ? "deleted" : "modified";
    const skipped = before.skipped ?? after.skipped ?? null;
    const row = q.addChange.get(id, turn, path, before.text, after.text, counts.added, counts.removed, status, skipped, Date.now())!;
    return { id: row.id, turn, path, ...counts, status, skipped: skipped ?? undefined };
  },
  changes: (id: string): ChangeMeta[] => q.changes.all(id).map((r) => ({ ...r, skipped: r.skipped ?? undefined })),
  change: (id: string, changeId: number) => q.change.get(changeId, id),
  savePicture: (id: string, turn: string, scene: Scene, changed: string[]) =>
    q.savePicture.run(id, turn, JSON.stringify(scene), JSON.stringify(changed), Date.now()),
  // A fork takes along the pictures and file changes of the turns it keeps: turns maps each kept
  // turn's uuid in the original chat to its uuid in the fork.
  copyTurns: (from: string, to: string, turns: Map<string, string>) =>
    db.transaction(() => {
      for (const r of q.pictures.all(from)) {
        const t = turns.get(r.turn_uuid);
        if (t) q.savePicture.run(to, t, r.scene, r.changed, r.created_at);
      }
      for (const r of q.changeRows.all(from)) {
        const t = turns.get(r.turn_uuid);
        if (t) q.addChange.get(to, t, r.path, r.before, r.after, r.added, r.removed, r.status, r.skipped, r.created_at);
      }
    })(),
  templates: (): Template[] => q.templates.all().map(asTemplate),
  template: (name: string): Template | null => {
    const r = q.template.get(name);
    return r ? asTemplate(r) : null;
  },
  saveTemplate: (t: Omit<Template, "updatedAt">) => q.saveTemplate.run(t.name, t.html, JSON.stringify(t.parts), t.description, Date.now()),
  deleteTemplate: (name: string) => q.deleteTemplate.run(name),
  pictures: (id: string) =>
    q.pictures.all(id).map((r) => ({
      turn: r.turn_uuid,
      scene: JSON.parse(r.scene) as Scene,
      changed: JSON.parse(r.changed) as string[],
      at: r.created_at,
    })),
};

function asTemplate(r: { name: string; html: string; parts: string; description: string; updated_at: number }): Template {
  return { name: r.name, html: r.html, parts: JSON.parse(r.parts), description: r.description, updatedAt: r.updated_at };
}
