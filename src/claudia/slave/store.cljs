(ns claudia.slave.store
  "The runner's own SQLite store (node:sqlite): one row per chat with what Claude Code's session
  files do not hold. The conversation itself stays in the session file."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(defonce ^:private db (atom nil))

(def ^:private columns
  ;; id = the Claude Code session id; at = last activity; ended = when the last turn ended;
  ;; seen = when the person last looked at its last turn; scene = the picture as JSON.
  ;; prev_color = the colour a passive (ash) chat had, for when it wakes.
  ;; How the last turn ended (the TypeScript Claudia's last_turns, for the hub's pushes and badge):
  ;; last_turn = the result's uuid, stopped / failed, reply = the start of Claude's reply (only when
  ;; it succeeded); ended is that turn's time, moved to when the work it left running settled.
  ;; talk = the chat's speaker (talking mode) is on; bell = it chimes and pushes when it finishes or waits.
  [:id :title :folder :color :archived :created :at :ended :seen :preview :scene :prev_color
   :last_turn :stopped :failed :reply :talk :bell])

(def ^:private schema
  "The tables, made when missing."
  "PRAGMA journal_mode = WAL;
              CREATE TABLE IF NOT EXISTS chats (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                folder TEXT,
                color TEXT,
                archived INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL,
                at INTEGER NOT NULL,
                ended INTEGER NOT NULL DEFAULT 0,
                seen INTEGER NOT NULL DEFAULT 0,
                preview TEXT NOT NULL DEFAULT '',
                scene TEXT
              );
              CREATE TABLE IF NOT EXISTS templates (
                name TEXT PRIMARY KEY COLLATE NOCASE,
                html TEXT NOT NULL,
                parts TEXT NOT NULL DEFAULT '{}',
                description TEXT NOT NULL DEFAULT '',
                at INTEGER NOT NULL
              );
              -- Small per-chat values as JSON (chat '' = the profile's own): settings, tasks, limits,
              -- formsSeen (the forms the chat's Claude last heard of).
              CREATE TABLE IF NOT EXISTS kv (
                chat TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (chat, key)
              );
              -- Files Claude changed, one row per file per turn, with the text before and after.
              CREATE TABLE IF NOT EXISTS changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat TEXT NOT NULL,
                turn TEXT NOT NULL,
                path TEXT NOT NULL,
                before TEXT,
                after TEXT,
                added INTEGER NOT NULL,
                removed INTEGER NOT NULL,
                status TEXT NOT NULL,
                skipped TEXT,
                at INTEGER NOT NULL
              );
              CREATE INDEX IF NOT EXISTS changes_by_chat ON changes (chat, id);
              -- One row per turn that changed the picture: turn = the user message the turn answers
              -- (or an id made for a turn Claude started itself); at = when it first drew.
              CREATE TABLE IF NOT EXISTS pictures (
                chat TEXT NOT NULL,
                turn TEXT NOT NULL,
                scene TEXT NOT NULL,
                at INTEGER NOT NULL,
                PRIMARY KEY (chat, turn)
              );")

(def ^:private added-columns
  "Columns of chats added after the table was first made: [name definition]."
  [["prev_color" "prev_color TEXT"]
   ["last_turn" "last_turn TEXT"]
   ["stopped" "stopped INTEGER NOT NULL DEFAULT 0"]
   ["failed" "failed INTEGER NOT NULL DEFAULT 0"]
   ["reply" "reply TEXT NOT NULL DEFAULT ''"]
   ["talk" "talk INTEGER NOT NULL DEFAULT 0"]
   ["bell" "bell INTEGER NOT NULL DEFAULT 1"]])

(defn- add-missing-columns! [^js d]
  (let [have (set (map #(.-name ^js %) (.all (.prepare d "PRAGMA table_info(chats)"))))]
    (doseq [[c sql] added-columns :when (not (have c))]
      (.exec d (str "ALTER TABLE chats ADD COLUMN " sql)))))

(defn- drop-chat-settings!
  "The per-chat form row is gone (2026-11-09): chat_settings' only kept value, the forms a chat's
  Claude last heard of, moves into kv as formsSeen, and the table goes."
  [^js d]
  (when (.get (.prepare d "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'chat_settings'"))
    (.exec d "BEGIN;
              INSERT OR IGNORE INTO kv (chat, key, value)
                SELECT id, 'formsSeen', forms_seen FROM chat_settings WHERE forms_seen IS NOT NULL;
              DROP TABLE chat_settings;
              COMMIT;")))

(defn open! [dir]
  (fs/mkdirSync dir #js {:recursive true})
  (let [d (DatabaseSync. (path/join dir "claudia.db"))]
    (.exec d schema)
    (add-missing-columns! d)
    (drop-chat-settings! d)
    (reset! db d)))

(defn- stmt ^js [sql] (.prepare ^js @db sql))

(defn- row [^js r]
  (when r
    {:id (.-id r)
     :title (.-title r)
     :folder (.-folder r)
     :color (.-color r)
     :archived (= 1 (.-archived r))
     :created (.-created r)
     :at (.-at r)
     :ended (.-ended r)
     :seen (.-seen r)
     :preview (.-preview r)
     :prev-color (.-prev_color r)
     :last-turn (.-last_turn r)
     :stopped (= 1 (.-stopped r))
     :failed (= 1 (.-failed r))
     :reply (or (.-reply r) "")
     :talk (= 1 (.-talk r))
     :bell (not= 0 (.-bell r))}))

(defn chats [] (mapv row (.all (stmt "SELECT * FROM chats ORDER BY at DESC"))))

(defn chat [id] (row (.get (stmt "SELECT * FROM chats WHERE id = ?") id)))

(defn add! [{:keys [id title folder color at]}]
  (.run (stmt "INSERT OR IGNORE INTO chats (id, title, folder, color, created, at, seen) VALUES (?, ?, ?, ?, ?, ?, ?)")
        id title folder color at at at))

(defn update-statement
  "The UPDATE for one chat's given columns (keys from columns; booleans stored as 0/1): [sql args],
  or nil when m sets none."
  [id m]
  (let [ks (filter (set (rest columns)) (keys m))]
    (when (seq ks)
      [(str "UPDATE chats SET " (str/join ", " (map #(str (name %) " = ?") ks)) " WHERE id = ?")
       (vec (concat (for [k ks :let [v (get m k)]] (if (boolean? v) (if v 1 0) v)) [id]))])))

(defn update!
  "Sets the given columns of one chat (keys from columns; booleans stored as 0/1)."
  [id m]
  (when-let [[sql args] (update-statement id m)]
    (let [^js st (stmt sql)]
      (.apply (.-run st) st (into-array args)))))

(defn scene [id]
  (let [^js r (.get (stmt "SELECT scene FROM chats WHERE id = ?") id)]
    (some-> r .-scene p/read-json)))

(defn save-scene! [id s]
  (.run (stmt "UPDATE chats SET scene = ? WHERE id = ?") (p/write-json s) id))

;; Templates: freeform blocks kept by name (save_template, the badge's "save as template").

(defn- template-row [^js r]
  (when r
    {:name (.-name r) :html (.-html r) :parts (p/read-json (.-parts r)) :description (.-description r)}))

(defn templates [] (mapv template-row (.all (stmt "SELECT * FROM templates ORDER BY name COLLATE NOCASE"))))

(defn template [template-name] (template-row (.get (stmt "SELECT * FROM templates WHERE name = ?") template-name)))

(defn save-template! [{template-name :name :keys [html parts description]}]
  (.run (stmt "INSERT INTO templates (name, html, parts, description, at) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(name) DO UPDATE SET html = excluded.html, parts = excluded.parts,
               description = excluded.description, at = excluded.at")
        template-name html (p/write-json (or parts {})) (or description "") (js/Date.now)))

(defn delete-template! [template-name] (.run (stmt "DELETE FROM templates WHERE name = ?") template-name))

;; kv ---------------------------------------------------------------------------------------------

(defn kv
  "The value kept under [chat key] (ClojureScript data), or nil."
  [chat-id k]
  (let [^js r (.get (stmt "SELECT value FROM kv WHERE chat = ? AND key = ?") chat-id k)]
    (some-> r .-value p/read-json)))

(defn kv-prefixed
  "Every [key value] kept for chat whose key starts with prefix."
  [chat-id prefix]
  (vec (for [^js r (.all (stmt "SELECT key, value FROM kv WHERE chat = ? AND substr(key, 1, length(?)) = ?")
                         chat-id prefix prefix)]
         [(.-key r) (p/read-json (.-value r))])))

(defn kv! [chat-id k v]
  (if (nil? v)
    (.run (stmt "DELETE FROM kv WHERE chat = ? AND key = ?") chat-id k)
    (.run (stmt "INSERT INTO kv (chat, key, value) VALUES (?, ?, ?)
                 ON CONFLICT (chat, key) DO UPDATE SET value = excluded.value")
          chat-id k (p/write-json v))))

;; The forms a chat's Claude last heard of ({form fingerprint}; nil: not told yet).

(defn forms-seen [id] (kv id "formsSeen"))

(defn save-forms-seen! [id seen] (kv! id "formsSeen" seen))

;; changes ----------------------------------------------------------------------------------------

(defn- change-row [^js r]
  {:id (.-id r) :turn (.-turn r) :path (.-path r) :added (.-added r) :removed (.-removed r)
   :status (.-status r) :skipped (.-skipped r) :at (.-at r)})

(defn add-change!
  "One changed file of a turn; returns its row (without the texts)."
  [chat-id {:keys [turn path before after added removed status skipped at]}]
  (let [^js r (.run (stmt "INSERT INTO changes (chat, turn, path, before, after, added, removed, status, skipped, at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    chat-id turn path before after added removed status skipped at)]
    (change-row (.get (stmt "SELECT * FROM changes WHERE id = ?") (.-lastInsertRowid r)))))

(defn changes
  "The chat's changes, oldest first (the newest 500)."
  [chat-id]
  (vec (reverse (map change-row (.all (stmt "SELECT id, turn, path, added, removed, status, skipped, at FROM changes
                                             WHERE chat = ? ORDER BY id DESC LIMIT 500") chat-id)))))

(defn change-texts
  "{:before :after} of one change of the chat, or nil."
  [chat-id id]
  (when-let [^js r (.get (stmt "SELECT before, after, skipped FROM changes WHERE chat = ? AND id = ?") chat-id id)]
    {:before (.-before r) :after (.-after r) :skipped (.-skipped r)}))
(defn save-picture!
  "The picture as a turn left it (so far): one row per chat and turn, its first time kept."
  [id turn s at]
  (.run (stmt "INSERT INTO pictures (chat, turn, scene, at) VALUES (?, ?, ?, ?)
               ON CONFLICT (chat, turn) DO UPDATE SET scene = excluded.scene")
        id turn (p/write-json s) at))

(defn pictures
  "The chat's pictures without their scenes, oldest first: [{:turn :title :at}]."
  [id]
  (mapv (fn [^js r] {:turn (.-turn r) :title (or (.-title r) "") :at (.-at r)})
        (.all (stmt "SELECT turn, json_extract(scene, '$.title') AS title, at FROM pictures
                     WHERE chat = ? ORDER BY at, rowid") id)))

(defn picture-scenes
  "Every earlier picture of the chat (its scenes)."
  [id]
  (mapv (fn [^js r] (p/read-json (.-scene r))) (.all (stmt "SELECT scene FROM pictures WHERE chat = ?") id)))

(defn picture [id turn]
  (some-> ^js (.get (stmt "SELECT scene FROM pictures WHERE chat = ? AND turn = ?") id turn)
          .-scene p/read-json))

(defn copy-turns!
  "A fork takes along the pictures and file changes of the turns it keeps (src/db.ts copyTurns):
  turns maps each kept turn's uuid in the chat `from` to its uuid in the fork `to`."
  [from to turns]
  (.exec ^js @db "BEGIN")
  (try
    (doseq [^js r (.all (stmt "SELECT turn, scene, at FROM pictures WHERE chat = ? ORDER BY at, rowid") from)
            :let [t (get turns (.-turn r))]
            :when t]
      (.run (stmt "INSERT INTO pictures (chat, turn, scene, at) VALUES (?, ?, ?, ?)
                   ON CONFLICT (chat, turn) DO UPDATE SET scene = excluded.scene")
            to t (.-scene r) (.-at r)))
    (doseq [^js r (.all (stmt "SELECT * FROM changes WHERE chat = ? ORDER BY id") from)
            :let [t (get turns (.-turn r))]
            :when t]
      (.run (stmt "INSERT INTO changes (chat, turn, path, before, after, added, removed, status, skipped, at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            to t (.-path r) (.-before r) (.-after r) (.-added r) (.-removed r) (.-status r) (.-skipped r) (.-at r)))
    (.exec ^js @db "COMMIT")
    (catch :default e (.exec ^js @db "ROLLBACK") (throw e))))
