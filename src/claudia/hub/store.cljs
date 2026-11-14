(ns claudia.hub.store
  "The hub's own store (node:sqlite, HUB_DATA/hub.db), the source of truth since README, Hub operations: servers and access: the
  servers it reaches, the people and who may use which server (the access grid), servers pairing
  over WireGuard, and for push the phones, what each person was told, and Live Activities."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.shared.protocol :as protocol]))

(defonce ^:private db (atom nil))

(def ^:private schema
  "PRAGMA journal_mode = WAL;
   PRAGMA foreign_keys = ON;
   -- A server: its short name (in addresses: /s/<name>/…), the label people see, and where its
   -- front listens on WireGuard (host:port).
   CREATE TABLE IF NOT EXISTS servers (
     name TEXT PRIMARY KEY,
     label TEXT NOT NULL,
     address TEXT NOT NULL,
     added_at INTEGER NOT NULL
   );
   -- The people of the access grid, by their sign-in email; admin = may open Servers and Access.
   CREATE TABLE IF NOT EXISTS people (
     email TEXT PRIMARY KEY,
     name TEXT NOT NULL DEFAULT '',
     admin INTEGER NOT NULL DEFAULT 0,
     added_at INTEGER NOT NULL
   );
   -- A tick in the grid: this person works on this server as their own profile there.
   CREATE TABLE IF NOT EXISTS access (
     email TEXT NOT NULL REFERENCES people (email) ON DELETE CASCADE,
     server TEXT NOT NULL REFERENCES servers (name) ON DELETE CASCADE,
     PRIMARY KEY (email, server)
   );
   -- A server joining from the Servers page: its WireGuard key, the address it was given, and the
   -- hash of the one-time secret it fetches its address with.
   CREATE TABLE IF NOT EXISTS pairings (
     server TEXT PRIMARY KEY REFERENCES servers (name) ON DELETE CASCADE,
     public_key TEXT NOT NULL UNIQUE,
     ip TEXT NOT NULL UNIQUE,
     secret_hash TEXT NOT NULL UNIQUE,
     created_at INTEGER NOT NULL,
     fetched_at INTEGER
   );
   -- A phone: its APNs token, which APNs (development = sandbox), the app's bundle id (the topic),
   -- the key alert texts are sealed with, its push-to-start token, when the hub started a Live
   -- Activity on it, and the badge it was last sent.
   CREATE TABLE IF NOT EXISTS devices (
     token TEXT PRIMARY KEY,
     email TEXT NOT NULL,
     env TEXT NOT NULL CHECK (env IN ('development', 'production')),
     topic TEXT NOT NULL,
     key TEXT NOT NULL,
     live_start TEXT,
     live_started_at INTEGER,
     badge INTEGER,
     added_at INTEGER NOT NULL,
     last_ok_at INTEGER
   );
   CREATE INDEX IF NOT EXISTS devices_email ON devices (email);
   -- What a person was already told (a question, a secret ask, a finished turn), so a restart or a
   -- reconnect never tells it again.
   CREATE TABLE IF NOT EXISTS pushed (
     email TEXT NOT NULL,
     server TEXT NOT NULL,
     chat TEXT NOT NULL,
     event TEXT NOT NULL,
     at INTEGER NOT NULL,
     PRIMARY KEY (email, server, chat, event)
   );
   -- A Live Activity on a phone, by ActivityKit's id, with the token its updates go to.
   CREATE TABLE IF NOT EXISTS live_activities (
     activity TEXT PRIMARY KEY,
     device TEXT NOT NULL REFERENCES devices (token) ON DELETE CASCADE,
     token TEXT NOT NULL,
     added_at INTEGER NOT NULL,
     ended_at INTEGER
   );
   CREATE INDEX IF NOT EXISTS live_activities_device ON live_activities (device);
   -- A Claude subscription's long-term token, saved by name (claudia.hub.tokens): sealed with the
   -- key in HUB_TOKEN_KEY_FILE (never in this file), who saved it and when. key = the name folded.
   CREATE TABLE IF NOT EXISTS claude_tokens (
     key TEXT PRIMARY KEY,
     name TEXT NOT NULL,
     sealed TEXT NOT NULL,
     saved_by TEXT NOT NULL,
     saved_at INTEGER NOT NULL
   );
   -- The one token every server's chats run on: which, who switched to it and when.
   CREATE TABLE IF NOT EXISTS claude_token_use (
     id INTEGER PRIMARY KEY CHECK (id = 1),
     key TEXT NOT NULL REFERENCES claude_tokens (key),
     used_by TEXT NOT NULL,
     used_at INTEGER NOT NULL
   );
   -- A saved token's last usage limits as a server's chats heard them (JSON [Limit]) and when.
   CREATE TABLE IF NOT EXISTS claude_token_limits (
     key TEXT PRIMARY KEY REFERENCES claude_tokens (key) ON DELETE CASCADE,
     limits TEXT NOT NULL,
     heard_at INTEGER NOT NULL
   );
   -- A person's GitHub token (claudia.hub.github-login): sealed with the key in HUB_TOKEN_KEY_FILE,
   -- the login it belongs to, where it came from (the page, or a server whose gh held it) and when.
   CREATE TABLE IF NOT EXISTS github_logins (
     email TEXT PRIMARY KEY,
     login TEXT NOT NULL,
     sealed TEXT NOT NULL,
     came_from TEXT NOT NULL,
     saved_at INTEGER NOT NULL
   );")

(defn open!
  "Opens (and makes) dir/hub.db."
  [dir]
  (fs/mkdirSync dir #js {:recursive true})
  (let [d (DatabaseSync. (path/join dir "hub.db"))]
    (.exec d schema)
    (reset! db d)))

(defn close! [] (some-> ^js @db .close) (reset! db nil))

(defn- now [] (js/Date.now))

(defn- stmt ^js [sql] (.prepare ^js @db sql))

(defn- exec! [sql & args]
  (let [^js s (stmt sql)]
    (.-changes (.apply (.-run s) s (into-array args)))))

(defn- ->map
  "A row (node:sqlite gives objects with no prototype, which js->clj leaves alone) as a map."
  [^js r]
  (when r (js->clj (js/Object.assign #js {} r) :keywordize-keys true)))

(defn- all [sql & args]
  (let [^js s (stmt sql)]
    (mapv ->map (.apply (.-all s) s (into-array args)))))

(defn- one [sql & args]
  (let [^js s (stmt sql)]
    (->map (.apply (.-get s) s (into-array args)))))

(defn transaction!
  "Runs f inside one transaction: all of it or nothing."
  [f]
  (.exec ^js @db "BEGIN IMMEDIATE")
  (try (let [v (f)] (.exec ^js @db "COMMIT") v)
       (catch :default e (.exec ^js @db "ROLLBACK") (throw e))))

;; Servers, people, access -----------------------------------------------------------------------

(defn empty-grid?
  "No server and no person yet (a fresh store: hub.json may be imported)."
  []
  (and (nil? (one "SELECT 1 AS n FROM servers LIMIT 1"))
       (nil? (one "SELECT 1 AS n FROM people LIMIT 1"))))

(defn servers [] (all "SELECT name, label, address FROM servers ORDER BY added_at, name"))

(defn server [server-name] (one "SELECT name, label, address FROM servers WHERE name = ?" server-name))

(defn save-server! [{server-name :name label :label address :address}]
  (exec! "INSERT INTO servers (name, label, address, added_at) VALUES (?, ?, ?, ?)
         ON CONFLICT (name) DO UPDATE SET label = excluded.label, address = excluded.address"
         server-name label address (now)))

(defn drop-server! [server-name] (exec! "DELETE FROM servers WHERE name = ?" server-name))

(defn- person-row [r]
  (when r (-> r (update :admin #(= 1 %)))))

(defn people []
  (mapv person-row (all "SELECT email, name, admin FROM people ORDER BY added_at, email")))

(defn person [email]
  (person-row (one "SELECT email, name, admin FROM people WHERE email = ?" email)))

(defn save-person!
  "Adds a person, or changes the name/admin given (nil leaves it)."
  [{:keys [email admin] person-name :name}]
  (exec! "INSERT INTO people (email, name, admin, added_at) VALUES (?, COALESCE(?, ''), COALESCE(?, 0), ?)
         ON CONFLICT (email) DO UPDATE SET name = COALESCE(?, people.name), admin = COALESCE(?, people.admin)"
         email person-name (when (some? admin) (if admin 1 0)) (now)
         person-name (when (some? admin) (if admin 1 0))))

(defn drop-person! [email] (exec! "DELETE FROM people WHERE email = ?" email))

(defn access [] (all "SELECT email, server FROM access ORDER BY email, server"))

(defn set-access! [email server-name granted]
  (if granted
    (exec! "INSERT OR IGNORE INTO access (email, server) VALUES (?, ?)" email server-name)
    (exec! "DELETE FROM access WHERE email = ? AND server = ?" email server-name)))

(defn allowed
  "The servers this person may use, in the order they were added."
  [email]
  (all "SELECT s.name, s.label, s.address FROM servers s JOIN access a ON a.server = s.name
        WHERE a.email = ? ORDER BY s.added_at, s.name" email))

(defn may? [email server-name]
  (some? (one "SELECT 1 AS n FROM access WHERE email = ? AND server = ?" email server-name)))

;; Pairings --------------------------------------------------------------------------------------

(defn- pairing-row [r]
  (when r {:server (:server r) :publicKey (:public_key r) :ip (:ip r)
           :createdAt (:created_at r) :fetchedAt (:fetched_at r)}))

(defn pairings []
  (mapv pairing-row (all "SELECT server, public_key, ip, created_at, fetched_at FROM pairings ORDER BY created_at")))

(defn add-paired!
  "A new server and its pairing, together or not at all."
  [s {:keys [public-key ip secret-hash]}]
  (transaction!
   (fn []
     (save-server! s)
     (exec! "INSERT INTO pairings (server, public_key, ip, secret_hash, created_at) VALUES (?, ?, ?, ?, ?)"
            (:name s) public-key ip secret-hash (now)))))

(defn pairing-by-secret [secret-hash]
  (pairing-row (one "SELECT server, public_key, ip, created_at, fetched_at FROM pairings WHERE secret_hash = ?" secret-hash)))

(defn mark-fetched! [server-name]
  (exec! "UPDATE pairings SET fetched_at = ? WHERE server = ? AND fetched_at IS NULL" (now) server-name))

;; Phones ----------------------------------------------------------------------------------------

(defn- device-row [r]
  (when r {:token (:token r) :email (:email r) :env (:env r) :topic (:topic r) :key (:key r)
           :live-start (:live_start r) :live-started-at (:live_started_at r) :badge (:badge r)
           :added-at (:added_at r) :last-ok-at (:last_ok_at r)}))

(defn save-device!
  "A phone registered (again). A token registered by another person moves to them and starts over (no
  card, no badge of the last person's); a push-to-start token left out keeps the one it had."
  [{:keys [token email env topic live-start] device-key :key}]
  (transaction!
   (fn []
     (let [was (:email (one "SELECT email FROM devices WHERE token = ?" token))]
       (when (and was (not= was email))
         (exec! "DELETE FROM live_activities WHERE device = ?" token)
         (exec! "UPDATE devices SET live_started_at = NULL, badge = NULL WHERE token = ?" token)))
     (exec! "INSERT INTO devices (token, email, env, topic, key, live_start, added_at) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (token) DO UPDATE SET email = excluded.email, env = excluded.env, topic = excluded.topic,
              key = excluded.key, live_start = COALESCE(excluded.live_start, devices.live_start)"
            token email env topic device-key live-start (now)))))

(defn devices [email]
  (mapv device-row (all "SELECT * FROM devices WHERE email = ? ORDER BY added_at" email)))

(defn drop-device!
  "A person removing one of their own phones: false when it isn't theirs."
  [token email]
  (pos? (exec! "DELETE FROM devices WHERE token = ? AND email = ?" token email)))

(defn forget-device! [token] (exec! "DELETE FROM devices WHERE token = ?" token))

(defn device-ok! [token] (exec! "UPDATE devices SET last_ok_at = ? WHERE token = ?" (now) token))

(defn set-badge! [token n] (exec! "UPDATE devices SET badge = ? WHERE token = ?" n token))

(defn set-live-start! [token start] (exec! "UPDATE devices SET live_start = ? WHERE token = ?" start token))

(defn live-started! [token at] (exec! "UPDATE devices SET live_started_at = ? WHERE token = ?" at token))

(defn push-people
  "The people with a phone, and since when they have had one: [{:email :since}]."
  []
  (all "SELECT email, MIN(added_at) AS since FROM devices GROUP BY email"))

(defn claim!
  "Takes the right to tell a person about an event: true only the first time."
  [email server-name chat event]
  (pos? (exec! "INSERT OR IGNORE INTO pushed (email, server, chat, event, at) VALUES (?, ?, ?, ?, ?)"
               email server-name chat event (now))))

(defn told? [email server-name chat event]
  (some? (one "SELECT 1 AS n FROM pushed WHERE email = ? AND server = ? AND chat = ? AND event = ?"
              email server-name chat event)))

(defn prune-pushed! [before] (exec! "DELETE FROM pushed WHERE at < ?" before))

;; Live Activities -------------------------------------------------------------------------------

(defn activity [id]
  (when-let [r (one "SELECT a.activity, a.device, a.token, a.added_at, a.ended_at, d.email
                     FROM live_activities a JOIN devices d ON d.token = a.device WHERE a.activity = ?" id)]
    {:activity (:activity r) :device (:device r) :token (:token r)
     :added-at (:added_at r) :ended-at (:ended_at r) :email (:email r)}))

(defn save-activity!
  "A phone's report of its activity's update token (a new one replaces the old; an ended one stays
  ended)."
  [id device token]
  (exec! "INSERT INTO live_activities (activity, device, token, added_at) VALUES (?, ?, ?, ?)
         ON CONFLICT (activity) DO UPDATE SET token = excluded.token
         WHERE live_activities.ended_at IS NULL AND live_activities.device = excluded.device"
         id device token (now)))

(defn activities
  "A phone's activities still on: [{:activity :token}]."
  [device]
  (all "SELECT activity, token FROM live_activities WHERE device = ? AND ended_at IS NULL ORDER BY added_at" device))

(defn end-activity! [id]
  (pos? (exec! "UPDATE live_activities SET ended_at = ? WHERE activity = ? AND ended_at IS NULL" (now) id)))

(defn drop-activity! [id] (exec! "DELETE FROM live_activities WHERE activity = ?" id))

(defn prune-activities! [before] (exec! "DELETE FROM live_activities WHERE ended_at < ?" before))

;; Claude tokens ----------------------------------------------------------------------------------

(defn claude-tokens
  "The saved tokens without their sealed text: [{:key :name :saved-by :saved-at}], by name."
  []
  (mapv (fn [r] {:key (:key r) :name (:name r) :saved-by (:saved_by r) :saved-at (:saved_at r)})
        (all "SELECT key, name, saved_by, saved_at FROM claude_tokens ORDER BY name COLLATE NOCASE")))

(defn claude-token-sealed [token-key] (:sealed (one "SELECT sealed FROM claude_tokens WHERE key = ?" token-key)))

(defn save-claude-token! [token-key token-name sealed by]
  (exec! "INSERT INTO claude_tokens (key, name, sealed, saved_by, saved_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (key) DO UPDATE SET name = excluded.name, sealed = excluded.sealed,
           saved_by = excluded.saved_by, saved_at = excluded.saved_at"
        token-key token-name sealed by (now)))

(defn drop-claude-token! [token-key] (exec! "DELETE FROM claude_tokens WHERE key = ?" token-key))

(defn claude-token-limits
  "Every saved token's last limits: {key {:limits [Limit] :heard-at ms}}."
  []
  (into {} (for [r (all "SELECT key, limits, heard_at FROM claude_token_limits")]
             [(:key r) {:limits (protocol/read-json (:limits r)) :heard-at (:heard_at r)}])))

(defn heard-claude-token-limits!
  "A token's limits heard at heard-at, kept when newer than what is kept."
  [token-key limits heard-at]
  (exec! "INSERT INTO claude_token_limits (key, limits, heard_at) VALUES (?, ?, ?)
         ON CONFLICT (key) DO UPDATE SET limits = excluded.limits, heard_at = excluded.heard_at
           WHERE excluded.heard_at > claude_token_limits.heard_at"
        token-key (protocol/write-json limits) heard-at))

(defn forget-claude-token-limits! [token-key] (exec! "DELETE FROM claude_token_limits WHERE key = ?" token-key))

(defn claude-token-use
  "{:key :name :by :at} of the token in use, or nil."
  []
  (when-let [r (one "SELECT u.key, t.name, u.used_by, u.used_at FROM claude_token_use u
                     JOIN claude_tokens t ON t.key = u.key WHERE u.id = 1")]
    {:key (:key r) :name (:name r) :by (:used_by r) :at (:used_at r)}))

(defn use-claude-token! [token-key by]
  (exec! "INSERT INTO claude_token_use (id, key, used_by, used_at) VALUES (1, ?, ?, ?)
         ON CONFLICT (id) DO UPDATE SET key = excluded.key, used_by = excluded.used_by, used_at = excluded.used_at"
        token-key by (now)))

;; GitHub logins ----------------------------------------------------------------------------------

(defn github-login
  "A person's GitHub login without the sealed token: {:login :came-from :saved-at}, or nil."
  [email]
  (when-let [r (one "SELECT login, came_from, saved_at FROM github_logins WHERE email = ?" email)]
    {:login (:login r) :came-from (:came_from r) :saved-at (:saved_at r)}))

(defn github-login-sealed [email] (:sealed (one "SELECT sealed FROM github_logins WHERE email = ?" email)))

(defn save-github-login! [email login sealed came-from]
  (exec! "INSERT INTO github_logins (email, login, sealed, came_from, saved_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (email) DO UPDATE SET login = excluded.login, sealed = excluded.sealed,
           came_from = excluded.came_from, saved_at = excluded.saved_at"
         email login sealed came-from (now)))
