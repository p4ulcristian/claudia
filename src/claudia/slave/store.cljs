(ns claudia.slave.store
  "The runner's own SQLite store (node:sqlite): one row per chat with what Claude Code's session
  files do not hold. The conversation itself stays in the session file."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.shared.protocol :as p]))

(defonce ^:private db (atom nil))

(def ^:private columns
  ;; id = the Claude Code session id; at = last activity; ended = when the last turn ended;
  ;; seen = when the person last looked at its last turn; scene = the picture as JSON.
  [:id :title :folder :color :archived :created :at :ended :seen :preview :scene])

(defn open! [dir]
  (fs/mkdirSync dir #js {:recursive true})
  (let [d (DatabaseSync. (path/join dir "claudia.db"))]
    (.exec d "PRAGMA journal_mode = WAL;
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
              -- Small per-chat values as JSON (chat '' = the profile's own): settings, tasks, limits.
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
     :preview (.-preview r)}))

(defn chats [] (mapv row (.all (stmt "SELECT * FROM chats ORDER BY at DESC"))))

(defn chat [id] (row (.get (stmt "SELECT * FROM chats WHERE id = ?") id)))

(defn add! [{:keys [id title folder color at]}]
  (.run (stmt "INSERT OR IGNORE INTO chats (id, title, folder, color, created, at, seen) VALUES (?, ?, ?, ?, ?, ?, ?)")
        id title folder color at at at))

(defn update!
  "Sets the given columns of one chat (keys from columns; booleans stored as 0/1)."
  [id m]
  (let [ks (filter (set (rest columns)) (keys m))]
    (when (seq ks)
      (let [^js st (stmt (str "UPDATE chats SET "
                                        (apply str (interpose ", " (map #(str (name %) " = ?") ks)))
                                        " WHERE id = ?"))
            vals (for [k ks :let [v (get m k)]] (if (boolean? v) (if v 1 0) v))]
        (.apply (.-run st) st (into-array (concat vals [id])))))))

(defn scene [id]
  (let [^js r (.get (stmt "SELECT scene FROM chats WHERE id = ?") id)]
    (some-> r .-scene p/read-json)))

(defn save-scene! [id scene]
  (.run (stmt "UPDATE chats SET scene = ? WHERE id = ?") (p/write-json scene) id))

;; kv ---------------------------------------------------------------------------------------------

(defn kv
  "The value kept under [chat key] (ClojureScript data), or nil."
  [chat k]
  (let [^js r (.get (stmt "SELECT value FROM kv WHERE chat = ? AND key = ?") chat k)]
    (some-> r .-value p/read-json)))

(defn kv! [chat k v]
  (if (nil? v)
    (.run (stmt "DELETE FROM kv WHERE chat = ? AND key = ?") chat k)
    (.run (stmt "INSERT INTO kv (chat, key, value) VALUES (?, ?, ?)
                 ON CONFLICT (chat, key) DO UPDATE SET value = excluded.value")
          chat k (p/write-json v))))

;; changes ----------------------------------------------------------------------------------------

(defn- change-row [^js r]
  {:id (.-id r) :turn (.-turn r) :path (.-path r) :added (.-added r) :removed (.-removed r)
   :status (.-status r) :skipped (.-skipped r) :at (.-at r)})

(defn add-change!
  "One changed file of a turn; returns its row (without the texts)."
  [chat {:keys [turn path before after added removed status skipped at]}]
  (let [^js r (.run (stmt "INSERT INTO changes (chat, turn, path, before, after, added, removed, status, skipped, at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    chat turn path before after added removed status skipped at)]
    (change-row (.get (stmt "SELECT * FROM changes WHERE id = ?") (.-lastInsertRowid r)))))

(defn changes
  "The chat's changes, oldest first (the newest 500)."
  [chat]
  (vec (reverse (map change-row (.all (stmt "SELECT id, turn, path, added, removed, status, skipped, at FROM changes
                                             WHERE chat = ? ORDER BY id DESC LIMIT 500") chat)))))

(defn change-texts
  "{:before :after} of one change of the chat, or nil."
  [chat id]
  (when-let [^js r (.get (stmt "SELECT before, after, skipped FROM changes WHERE chat = ? AND id = ?") chat id)]
    {:before (.-before r) :after (.-after r) :skipped (.-skipped r)}))
(defn save-picture!
  "The picture as a turn left it (so far): one row per chat and turn, its first time kept."
  [id turn scene at]
  (.run (stmt "INSERT INTO pictures (chat, turn, scene, at) VALUES (?, ?, ?, ?)
               ON CONFLICT (chat, turn) DO UPDATE SET scene = excluded.scene")
        id turn (p/write-json scene) at))

(defn pictures
  "The chat's pictures without their scenes, oldest first: [{:turn :title :at}]."
  [id]
  (mapv (fn [^js r] {:turn (.-turn r) :title (or (.-title r) "") :at (.-at r)})
        (.all (stmt "SELECT turn, json_extract(scene, '$.title') AS title, at FROM pictures
                     WHERE chat = ? ORDER BY at, rowid") id)))

(defn picture [id turn]
  (some-> ^js (.get (stmt "SELECT scene FROM pictures WHERE chat = ? AND turn = ?") id turn)
          .-scene p/read-json))
