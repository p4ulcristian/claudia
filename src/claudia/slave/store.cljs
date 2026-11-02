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
