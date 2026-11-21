(ns claudia.slave.store
  "The runner's own SQLite store (node:sqlite): one row per chat with what Claude Code's session
  files do not hold. The conversation itself stays in the session file."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.shared.sounds :as sounds]
            [claudia.shared.voices :as voices]
            [claudia.slave.upgrade :as upgrade]))

(defonce ^:private db (atom nil))

(def ^:private columns
  ;; id = the Claude Code session id; at = last activity; ended = when the last turn ended;
  ;; seen = when the person last looked at its last turn; scene = the picture as JSON.
  ;; prev_color = the colour a passive (ash) chat had, for when it wakes.
  ;; How the last turn ended (the TypeScript Claudia's last_turns, for the hub's pushes and badge):
  ;; last_turn = the result's uuid, stopped / failed, reply = the start of Claude's reply (only when
  ;; it succeeded); ended is that turn's time, moved to when the work it left running settled.
  ;; voice = the chat's voice (claudia.shared.voices; "" = Off; the old talk column, its speaker on,
  ;; is read once into it); bell = it chimes and pushes when it finishes or waits; sound = which sound
  ;; it rings (claudia.shared.sounds, README decision 90).
  ;; chat_key = the random key its sessions get as CLAUDIA_CHAT_KEY (the sudo shim sends it); root =
  ;; its root switch is on (README decision 52).
  [:id :title :folder :color :archived :created :at :ended :seen :preview :scene :prev_color
   :last_turn :stopped :failed :reply :voice :bell :sound :chat_key :root])

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
              -- One row per turn that changed the picture: turn = the user message the turn answers
              -- (or an id made for a turn Claude started itself); at = when it first drew.
              CREATE TABLE IF NOT EXISTS pictures (
                chat TEXT NOT NULL,
                turn TEXT NOT NULL,
                scene TEXT NOT NULL,
                at INTEGER NOT NULL,
                PRIMARY KEY (chat, turn)
              );
              -- Claudia's Trash (README decision 63): one row per folder moved to
              -- RUNNER_DATA/trash/<id>/<name>; path = where it was, size in bytes (null: not measured),
              -- by = who moved it (the front's X-Claudia-By).
              -- Voice messages (README decision 93): a chat with a voice that ended a turn by itself
              -- or asks leaves its reply (text) or ask (JSON) here until a page says it was heard or
              -- swiped away, at most a day (claudia.slave.voice-messages).
              CREATE TABLE IF NOT EXISTS voice_messages (
                chat TEXT NOT NULL,
                id TEXT NOT NULL,
                voice TEXT NOT NULL,
                text TEXT,
                ask TEXT,
                at INTEGER NOT NULL,
                PRIMARY KEY (chat, id)
              );
              -- A chat's proposed System label (README decision 96, its system_label tool): nothing
              -- applies until the person accepts it on System (the hub's labels) or says no.
              CREATE TABLE IF NOT EXISTS system_proposals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat TEXT NOT NULL,
                server TEXT NOT NULL,
                kind TEXT NOT NULL,
                match TEXT NOT NULL,
                project TEXT NOT NULL,
                why TEXT NOT NULL DEFAULT '',
                at INTEGER NOT NULL
              );
              CREATE TABLE IF NOT EXISTS trash (
                id TEXT PRIMARY KEY,
                path TEXT NOT NULL,
                name TEXT NOT NULL,
                at INTEGER NOT NULL,
                size INTEGER,
                by TEXT
              );")

(def ^:private added-columns
  "Columns of chats added after the table was first made: [name definition]."
  [["prev_color" "prev_color TEXT"]
   ["last_turn" "last_turn TEXT"]
   ["stopped" "stopped INTEGER NOT NULL DEFAULT 0"]
   ["failed" "failed INTEGER NOT NULL DEFAULT 0"]
   ["reply" "reply TEXT NOT NULL DEFAULT ''"]
   ["talk" "talk INTEGER NOT NULL DEFAULT 0"]
   ["bell" "bell INTEGER NOT NULL DEFAULT 1"]
   ["chat_key" "chat_key TEXT"]
   ["root" "root INTEGER NOT NULL DEFAULT 0"]
   ["voice" "voice TEXT NOT NULL DEFAULT ''"]
   ["sound" "sound TEXT NOT NULL DEFAULT 'bell'"]])

(defn- talk->voice!
  "Voices came (README decision 21, 2026-11-20): a chat whose speaker was on speaks in the first voice."
  [^js d]
  (.run (.prepare d "UPDATE chats SET voice = ? WHERE talk = 1 AND voice = ''") voices/default-id))

(defn- add-missing-columns! [^js d]
  (let [have (set (map #(.-name ^js %) (.all (.prepare d "PRAGMA table_info(chats)"))))]
    (doseq [[c sql] added-columns :when (not (have c))]
      (.exec d (str "ALTER TABLE chats ADD COLUMN " sql)))
    (when-not (have "voice") (talk->voice! d))))

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

(defn- drop-changes!
  "The changed-files tracking is gone (README decision 97): the files Claude changed per turn, with
  their texts before and after, go with the table changes."
  [^js d]
  (.exec d "DROP TABLE IF EXISTS changes"))

;; Version 1 (2026-11-16, README decision 39): every saved picture's blocks are forms.
(def ^:private version 1)

(defn- rewrite-scenes!
  "Every saved picture (the chats' and the history's) rewritten by f, in one transaction."
  [^js d f]
  (let [redo (fn [json] (p/write-json (f (p/read-json json))))
        ^js chat-st (.prepare d "UPDATE chats SET scene = ? WHERE id = ?")
        ^js pic-st (.prepare d "UPDATE pictures SET scene = ? WHERE chat = ? AND turn = ?")]
    (.exec d "BEGIN")
    (try
      (doseq [^js r (.all (.prepare d "SELECT id, scene FROM chats WHERE scene IS NOT NULL"))]
        (.run chat-st (redo (.-scene r)) (.-id r)))
      (doseq [^js r (.all (.prepare d "SELECT chat, turn, scene FROM pictures"))]
        (.run pic-st (redo (.-scene r)) (.-chat r) (.-turn r)))
      (.exec d (str "PRAGMA user_version = " version))
      (.exec d "COMMIT")
      (catch :default e (.exec d "ROLLBACK") (throw e)))))

(defn- upgrade! [^js d]
  (when (< (.-user_version (.get (.prepare d "PRAGMA user_version"))) version)
    (rewrite-scenes! d upgrade/scene)))

(defn open! [dir]
  (fs/mkdirSync dir #js {:recursive true})
  (let [d (DatabaseSync. (path/join dir "claudia.db"))]
    (.exec d schema)
    (add-missing-columns! d)
    (drop-chat-settings! d)
    (drop-changes! d)
    (upgrade! d)
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
     :voice (voices/voice-id (.-voice r))
     :bell (not= 0 (.-bell r))
     :sound (sounds/sound-id (.-sound r))
     :chat-key (.-chat_key r)
     :root (= 1 (.-root r))}))

(defn chats [] (mapv row (.all (stmt "SELECT * FROM chats ORDER BY at DESC"))))

(defn chat [id] (row (.get (stmt "SELECT * FROM chats WHERE id = ?") id)))

(defn chat-by-key
  "The chat whose sessions run with chat-key (CLAUDIA_CHAT_KEY), or nil."
  [chat-key]
  (when (seq chat-key) (row (.get (stmt "SELECT * FROM chats WHERE chat_key = ?") chat-key))))

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

;; Templates: html blocks kept by name (save_template, the badge's "save as template").

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

;; Voice messages (README decision 93) ------------------------------------------------------------

(defn- voice-message-row [^js r]
  (cond-> {:chat (.-chat r) :id (.-id r) :voice (.-voice r) :at (.-at r)}
    (.-text r) (assoc :text (.-text r))
    (.-ask r) (assoc :ask (p/read-json (.-ask r)))))

(defn add-voice-message!
  "Keeps a voice message {:chat :id :voice :text or :ask :at} (the same chat and id: replaced)."
  [{chat-id :chat :keys [id voice text ask at]}]
  (.run (stmt "INSERT OR REPLACE INTO voice_messages (chat, id, voice, text, ask, at) VALUES (?, ?, ?, ?, ?, ?)")
        chat-id id voice text (some-> ask p/write-json) at))

(defn voice-messages
  "Every voice message kept, oldest first."
  []
  (mapv voice-message-row (.all (stmt "SELECT * FROM voice_messages ORDER BY at, rowid"))))

(defn drop-voice-message! [chat-id id] (.run (stmt "DELETE FROM voice_messages WHERE chat = ? AND id = ?") chat-id id))

(defn drop-chat-voice-messages! [chat-id] (.run (stmt "DELETE FROM voice_messages WHERE chat = ?") chat-id))

(defn drop-voice-messages-before! [t] (.run (stmt "DELETE FROM voice_messages WHERE at < ?") t))

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
  "A fork takes along the pictures of the turns it keeps (src/db.ts copyTurns):
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
    (.exec ^js @db "COMMIT")
    (catch :default e (.exec ^js @db "ROLLBACK") (throw e))))

;; trash ------------------------------------------------------------------------------------------

(defn- trash-item-row [^js r]
  (when r {:id (.-id r) :path (.-path r) :name (.-name r) :at (.-at r) :size (.-size r) :by (.-by r)}))

(defn trash-items
  "Every folder in the Trash, newest first."
  []
  (mapv trash-item-row (.all (stmt "SELECT * FROM trash ORDER BY at DESC"))))

(defn drop-trash! [id] (.run (stmt "DELETE FROM trash WHERE id = ?") id))

;; A chat's rows for a move to another server (README decision 82; claudia.slave.move) -------------

(def chat-columns "Every column of chats, as keywords named like the columns." columns)

(defn- plain-row
  "A row object as a map of its columns (keywords named like them)."
  [^js r]
  (into {} (for [k (js/Object.keys r)] [(keyword k) (aget r k)])))

(defn export-rows
  "Chat id's own rows: {:row (every column) :kv [[key json-text]] :pictures [{:turn :scene (JSON
  text) :at}]}, or nil."
  [id]
  (when-let [^js r (.get (stmt "SELECT * FROM chats WHERE id = ?") id)]
    {:row (plain-row r)
     :kv (mapv (fn [^js x] [(.-key x) (.-value x)]) (.all (stmt "SELECT key, value FROM kv WHERE chat = ?") id))
     :pictures (mapv plain-row (.all (stmt "SELECT turn, scene, at FROM pictures WHERE chat = ? ORDER BY at, rowid") id))}))

(defn- drop-chat-rows!
  "Every row of chat id: the chat, its kv values, pictures and voice messages."
  [id]
  (doseq [t ["chats WHERE id = ?" "kv WHERE chat = ?" "pictures WHERE chat = ?" "voice_messages WHERE chat = ?"]]
    (.run (stmt (str "DELETE FROM " t)) id)))

(defn- insert-row!
  "One chats row from m (keys: only columns of chats, the caller's whitelist)."
  [m]
  (let [ks (filter (set columns) (keys m))
        ^js st (stmt (str "INSERT INTO chats (" (str/join ", " (map name ks)) ") VALUES ("
                          (str/join ", " (repeat (count ks) "?")) ")"))]
    (.apply (.-run st) st (into-array (map #(get m %) ks)))))

(defn- insert-copied!
  "A copied chat's kv values ({key json-text}) and pictures."
  [id kvs pics]
  (doseq [[k v] kvs] (.run (stmt "INSERT INTO kv (chat, key, value) VALUES (?, ?, ?)") id k v))
  (doseq [{turn :turn s :scene at :at} pics]
    (.run (stmt "INSERT INTO pictures (chat, turn, scene, at) VALUES (?, ?, ?, ?) ON CONFLICT (chat, turn) DO NOTHING")
          id turn s at)))

(defn import-chat!
  "A moved chat's rows, in one transaction: whatever rows its id had here (the copy left behind by an
  earlier move away) give way to row (columns of chats), kv ({key json-text}) and pictures."
  [{r :row kvs :kv pics :pictures}]
  (let [id (:id r)]
    (.exec ^js @db "BEGIN")
    (try
      (drop-chat-rows! id)
      (insert-row! r)
      (insert-copied! id kvs pics)
      (.exec ^js @db "COMMIT")
      (catch :default e (.exec ^js @db "ROLLBACK") (throw e)))))

;; System proposals (README decision 96) -----------------------------------------------------------

(defn add-proposal!
  "Keeps a chat's proposed label; its id."
  [{chat-id :chat :keys [server kind match project why at]}]
  (.-lastInsertRowid (.run (stmt "INSERT INTO system_proposals (chat, server, kind, match, project, why, at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                           chat-id server kind match project why at)))

(defn proposals
  "Every kept proposal, oldest first, as {:id :chat :server :kind :match :project :why :at}."
  []
  (mapv (fn [^js r] {:id (.-id r) :chat (.-chat r) :server (.-server r) :kind (.-kind r) :match (.-match r)
                     :project (.-project r) :why (.-why r) :at (.-at r)})
        (.all (stmt "SELECT * FROM system_proposals ORDER BY at, id"))))

(defn drop-proposal!
  "Takes a proposal away; how many went (0 or 1)."
  [id]
  (.-changes (.run (stmt "DELETE FROM system_proposals WHERE id = ?") id)))
