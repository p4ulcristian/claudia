(ns claudia.hub.store
  "The hub's own store (node:sqlite, HUB_DATA/hub.db), the source of truth since README, Hub operations: servers and access: the
  servers it reaches, the people and who may use which server (the access grid), servers pairing
  (their one-time secrets for fetching a hub key), and for push the phones, what each person was told, and Live Activities."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [claudia.shared.protocol :as protocol]))

(defonce ^:private db (atom nil))

(def ^:private schema
  "PRAGMA journal_mode = WAL;
   PRAGMA foreign_keys = ON;
   -- A server: its short name (in addresses: /s/<name>/…), the label people see, and where the hub
   -- reaches its front (host:port, on the private network joining them).
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
   -- A person's profile name, the same on every server: their chats there run as the agent user
   -- <profile>-claudia-agent, and the hub signs SSH certificates only for that name (README
   -- decisions 51 and 53). No row: no certificates for them.
   CREATE TABLE IF NOT EXISTS profiles (
     email TEXT PRIMARY KEY REFERENCES people (email) ON DELETE CASCADE,
     profile TEXT NOT NULL UNIQUE
   );
   -- A tick in the grid: this person works on this server as their own profile there.
   CREATE TABLE IF NOT EXISTS access (
     email TEXT NOT NULL REFERENCES people (email) ON DELETE CASCADE,
     server TEXT NOT NULL REFERENCES servers (name) ON DELETE CASCADE,
     PRIMARY KEY (email, server)
   );
   -- A write tick: this person (email) may write to the chats of another (owner), on every server
   -- where the owner has a profile. Without one, another's chats are read only.
   CREATE TABLE IF NOT EXISTS writers (
     email TEXT NOT NULL REFERENCES people (email) ON DELETE CASCADE,
     owner TEXT NOT NULL REFERENCES people (email) ON DELETE CASCADE,
     PRIMARY KEY (email, owner),
     CHECK (email <> owner)
   );
   -- A person's profile picture (the page's profile circles): PNG or WebP bytes, set by an admin.
   CREATE TABLE IF NOT EXISTS pictures (
     email TEXT PRIMARY KEY REFERENCES people (email) ON DELETE CASCADE,
     type TEXT NOT NULL,
     data BLOB NOT NULL,
     at INTEGER NOT NULL
   );
   -- The WireGuard pairings of before README decision 47 (servers now reach the hub over any
   -- private network and fetch a hub key): gone.
   DROP TABLE IF EXISTS pairings;
   -- A server paired from the Servers page: the hash of the one-time secret it fetches its hub
   -- key with (README decision 47), and when it did.
   CREATE TABLE IF NOT EXISTS pairing_secrets (
     server TEXT PRIMARY KEY REFERENCES servers (name) ON DELETE CASCADE,
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
   -- The one token every server's chats ran on before README decision 57: gone, and carried over
   -- to nobody.
   DROP TABLE IF EXISTS claude_token_use;
   -- The token a person last picked in a chat's header (README decision 57): their chats without
   -- a pick run on it, on every server. Kept when that token is removed (it counts again if one is
   -- saved under the same name).
   CREATE TABLE IF NOT EXISTS claude_token_picks (
     email TEXT PRIMARY KEY,
     key TEXT NOT NULL,
     picked_at INTEGER NOT NULL
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
   );
   -- A person's Sentinel API key (claudia.hub.sentinel-key, README decision 85): sealed with the
   -- key in HUB_TOKEN_KEY_FILE, its hint (first 8 characters) and fingerprint, and when it was saved.
   -- sealed null: the person removed it, so their servers drop theirs; no row: never set here.
   CREATE TABLE IF NOT EXISTS sentinel_keys (
     email TEXT PRIMARY KEY,
     sealed TEXT,
     hint TEXT,
     fingerprint TEXT,
     saved_at INTEGER NOT NULL
   );
   -- A person's connection to the GitHub App: their login, the refresh token (months) and the user
   -- token (hours) sealed, and when each runs out (null: GitHub gave one that does not expire).
   CREATE TABLE IF NOT EXISTS github_users (
     email TEXT PRIMARY KEY REFERENCES people (email) ON DELETE CASCADE,
     login TEXT NOT NULL,
     sealed_access TEXT NOT NULL,
     access_expires_at INTEGER,
     sealed_refresh TEXT,
     refresh_expires_at INTEGER,
     saved_at INTEGER NOT NULL
   );
   -- A person's own order of their chats and their groups (claudia.shared.order), as JSON: the same
   -- list on every device of theirs.
   CREATE TABLE IF NOT EXISTS chat_orders (
     email TEXT PRIMARY KEY REFERENCES people (email) ON DELETE CASCADE,
     items TEXT NOT NULL,
     at INTEGER NOT NULL
   );
   -- A person's bookmarked folders (claudia.shared.bookmarks, README decision 61), as JSON: the
   -- same list on every device of theirs.
   CREATE TABLE IF NOT EXISTS bookmarks (
     email TEXT PRIMARY KEY REFERENCES people (email) ON DELETE CASCADE,
     items TEXT NOT NULL,
     at INTEGER NOT NULL
   );
   -- A person's dictation choices (claudia.hub.listen-prefs): the words they said No to in the
   -- chat, the words they added from the chat, and whether the chat offers words; JSON, the same on
   -- every device of theirs.
   CREATE TABLE IF NOT EXISTS listen_prefs (
     email TEXT PRIMARY KEY REFERENCES people (email) ON DELETE CASCADE,
     prefs TEXT NOT NULL,
     at INTEGER NOT NULL
   );
   -- What a person's right half shows (claudia.shared.views, README decision 91): the same on
   -- every device of theirs. view: the pile of sheets over the Drawing board, their names bottom to
   -- top, space separated ('' for none; one name, or picture, before decision 91).
   CREATE TABLE IF NOT EXISTS right_views (
     email TEXT PRIMARY KEY REFERENCES people (email) ON DELETE CASCADE,
     view TEXT NOT NULL,
     at INTEGER NOT NULL
   );
   -- What a page calls a project on System (README decision 96): a label for a unit, container,
   -- port or program, per server or for every one (server '*'), written by admins.
   CREATE TABLE IF NOT EXISTS system_labels (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     server TEXT NOT NULL,
     kind TEXT NOT NULL CHECK (kind IN ('unit', 'container', 'port', 'process')),
     match TEXT NOT NULL,
     project TEXT NOT NULL,
     by TEXT NOT NULL,
     at INTEGER NOT NULL,
     UNIQUE (server, kind, match)
   );
   -- The release builder (claudia.hub.releases, README decision 29): one at a time, the host its
   -- builder key named and the SHA-256 of its secret (its bearer token; the secret is never kept).
   CREATE TABLE IF NOT EXISTS release_builder (
     id INTEGER PRIMARY KEY CHECK (id = 1),
     host TEXT NOT NULL,
     token_hash TEXT NOT NULL,
     paired_at INTEGER NOT NULL,
     seen_at INTEGER
   );")

(defn open!
  "Opens (and makes) dir/hub.db."
  [dir]
  (fs/mkdirSync dir #js {:recursive true})
  (let [d (DatabaseSync. (path/join dir "hub.db"))]
    (.exec d schema)
    (reset! db d)))

(defn close! [] (some-> ^js @db .close) (reset! db nil))

(defn database
  "The open store, for a part that keeps a table of its own in it (System's history of the gate,
  claudia.node.history)."
  ^js []
  @db)

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

(def ^:private person-select
  "SELECT p.email, p.name, p.admin, COALESCE(pr.profile, '') AS profile
   FROM people p LEFT JOIN profiles pr ON pr.email = p.email")

(defn people []
  (mapv person-row (all (str person-select " ORDER BY p.added_at, p.email"))))

(defn picture
  "A person's picture: {:type :data (bytes) :at}, or nil."
  [email]
  (one "SELECT type, data, at FROM pictures WHERE email = ?" email))

(defn picture-times
  "When each person with a picture last got it: {email at}."
  []
  (into {} (map (juxt :email :at)) (all "SELECT email, at FROM pictures")))

(defn save-picture!
  "Sets a person's picture (type: image/png or image/webp; data: bytes)."
  [email kind data at]
  (exec! "INSERT INTO pictures (email, type, data, at) VALUES (?, ?, ?, ?)
          ON CONFLICT (email) DO UPDATE SET type = excluded.type, data = excluded.data, at = excluded.at"
         email kind data at))

(defn person [email]
  (person-row (one (str person-select " WHERE p.email = ?") email)))

(defn save-person!
  "Adds a person, or changes the name/admin given (nil leaves it)."
  [{:keys [email admin] person-name :name}]
  (exec! "INSERT INTO people (email, name, admin, added_at) VALUES (?, COALESCE(?, ''), COALESCE(?, 0), ?)
         ON CONFLICT (email) DO UPDATE SET name = COALESCE(?, people.name), admin = COALESCE(?, people.admin)"
         email person-name (when (some? admin) (if admin 1 0)) (now)
         person-name (when (some? admin) (if admin 1 0))))

(defn set-profile!
  "Sets a person's profile name; blank takes it away."
  [email profile]
  (if (seq profile)
    (exec! "INSERT INTO profiles (email, profile) VALUES (?, ?)
            ON CONFLICT (email) DO UPDATE SET profile = excluded.profile" email profile)
    (exec! "DELETE FROM profiles WHERE email = ?" email)))

(defn email-of-profile
  "The email of the person whose profile name this is, or nil."
  [profile]
  (:email (one "SELECT email FROM profiles WHERE profile = ?" profile)))

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

(defn writers [] (all "SELECT email, owner FROM writers ORDER BY email, owner"))

(defn set-writer! [email owner granted]
  (if granted
    (exec! "INSERT OR IGNORE INTO writers (email, owner) VALUES (?, ?)" email owner)
    (exec! "DELETE FROM writers WHERE email = ? AND owner = ?" email owner)))

(defn writes-for
  "The emails whose chats this person may write to."
  [email]
  (mapv :owner (all "SELECT owner FROM writers WHERE email = ? ORDER BY owner" email)))

(defn may? [email server-name]
  (some? (one "SELECT 1 AS n FROM access WHERE email = ? AND server = ?" email server-name)))

;; Pairings --------------------------------------------------------------------------------------

(defn- pairing-row [r]
  (when r {:server (:server r) :createdAt (:created_at r) :fetchedAt (:fetched_at r)}))

(defn pairings []
  (mapv pairing-row (all "SELECT server, created_at, fetched_at FROM pairing_secrets ORDER BY created_at")))

(defn add-pairing!
  "The server (new or kept) and its new pairing secret, together or not at all."
  [s secret-hash]
  (transaction!
   (fn []
     (save-server! s)
     (exec! "INSERT INTO pairing_secrets (server, secret_hash, created_at) VALUES (?, ?, ?)
             ON CONFLICT (server) DO UPDATE SET secret_hash = excluded.secret_hash,
               created_at = excluded.created_at, fetched_at = NULL"
            (:name s) secret-hash (now)))))

(defn pairing-by-secret [secret-hash]
  (pairing-row (one "SELECT server, created_at, fetched_at FROM pairing_secrets WHERE secret_hash = ?" secret-hash)))

(defn mark-fetched! [server-name]
  (exec! "UPDATE pairing_secrets SET fetched_at = ? WHERE server = ? AND fetched_at IS NULL" (now) server-name))

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

(defn claude-token-picks
  "Each person's last pick: {email {:key :at}}."
  []
  (into {} (for [r (all "SELECT email, key, picked_at FROM claude_token_picks")]
             [(:email r) {:key (:key r) :at (:picked_at r)}])))

(defn pick-claude-token!
  "The token a person picked last in a chat's header."
  [email token-key]
  (exec! "INSERT INTO claude_token_picks (email, key, picked_at) VALUES (?, ?, ?)
         ON CONFLICT (email) DO UPDATE SET key = excluded.key, picked_at = excluded.picked_at"
         email token-key (now)))

(defn people-on
  "The people with their own profile on a server (a tick in the grid): [{:email :profile}]."
  [server-name]
  (all "SELECT a.email, COALESCE(pr.profile, '') AS profile FROM access a
        LEFT JOIN profiles pr ON pr.email = a.email WHERE a.server = ? ORDER BY a.email" server-name))

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

;; Sentinel keys ----------------------------------------------------------------------------------

(defn sentinel-key
  "A person's Sentinel key without the sealed key: {:hint :fingerprint :saved-at :removed}, or nil
  when they never set one here."
  [email]
  (when-let [r (one "SELECT sealed IS NULL AS removed, hint, fingerprint, saved_at FROM sentinel_keys WHERE email = ?" email)]
    {:hint (:hint r) :fingerprint (:fingerprint r) :saved-at (:saved_at r) :removed (= 1 (:removed r))}))

(defn sentinel-key-sealed [email] (:sealed (one "SELECT sealed FROM sentinel_keys WHERE email = ?" email)))

(defn save-sentinel-key!
  "Keeps a person's sealed key (sealed, hint and fingerprint nil: removed)."
  [email sealed key-hint fingerprint]
  (exec! "INSERT INTO sentinel_keys (email, sealed, hint, fingerprint, saved_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (email) DO UPDATE SET sealed = excluded.sealed, hint = excluded.hint,
           fingerprint = excluded.fingerprint, saved_at = excluded.saved_at"
         email sealed key-hint fingerprint (now)))

;; The release builder ---------------------------------------------------------------------------

(defn github-users
  "Every person's GitHub App connection."
  []
  (all "SELECT email, login, sealed_access, access_expires_at, sealed_refresh, refresh_expires_at, saved_at
        FROM github_users ORDER BY email"))

(defn github-user [email]
  (one "SELECT email, login, sealed_access, access_expires_at, sealed_refresh, refresh_expires_at, saved_at
        FROM github_users WHERE email = ?" email))

(defn save-github-user! [{:keys [email login sealed-access access-expires-at sealed-refresh refresh-expires-at]}]
  (exec! "INSERT INTO github_users (email, login, sealed_access, access_expires_at, sealed_refresh, refresh_expires_at, saved_at)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (email) DO UPDATE SET login = excluded.login, sealed_access = excluded.sealed_access,
            access_expires_at = excluded.access_expires_at, sealed_refresh = excluded.sealed_refresh,
            refresh_expires_at = excluded.refresh_expires_at, saved_at = excluded.saved_at"
         email login sealed-access access-expires-at sealed-refresh refresh-expires-at (now)))

(defn drop-github-user! [email] (exec! "DELETE FROM github_users WHERE email = ?" email))

(defn drop-github-login! [email] (exec! "DELETE FROM github_logins WHERE email = ?" email))

(defn chat-order
  "A person's chat order: {:items (JSON text) :at}, or nil."
  [email]
  (one "SELECT items, at FROM chat_orders WHERE email = ?" email))

(defn save-chat-order! [email items-json]
  (exec! "INSERT INTO chat_orders (email, items, at) VALUES (?, ?, ?)
          ON CONFLICT (email) DO UPDATE SET items = excluded.items, at = excluded.at"
         email items-json (now)))

(defn bookmarks
  "A person's bookmarked folders: {:items (JSON text) :at}, or nil."
  [email]
  (one "SELECT items, at FROM bookmarks WHERE email = ?" email))

(defn save-bookmarks! [email items-json]
  (exec! "INSERT INTO bookmarks (email, items, at) VALUES (?, ?, ?)
          ON CONFLICT (email) DO UPDATE SET items = excluded.items, at = excluded.at"
         email items-json (now)))

(defn listen-prefs
  "A person's dictation choices: {:prefs (JSON text) :at}, or nil."
  [email]
  (one "SELECT prefs, at FROM listen_prefs WHERE email = ?" email))

(defn save-listen-prefs! [email prefs-json]
  (exec! "INSERT INTO listen_prefs (email, prefs, at) VALUES (?, ?, ?)
          ON CONFLICT (email) DO UPDATE SET prefs = excluded.prefs, at = excluded.at"
         email prefs-json (now)))

(defn right-view
  "What a person's right half shows: {:view (the pile's names, space separated) :at}, or nil."
  [email]
  (one "SELECT view, at FROM right_views WHERE email = ?" email))

(defn save-right-view! [email view]
  (exec! "INSERT INTO right_views (email, view, at) VALUES (?, ?, ?)
          ON CONFLICT (email) DO UPDATE SET view = excluded.view, at = excluded.at"
         email view (now)))

(defn release-builder
  "The release builder: {:host :token-hash :paired-at :seen-at}, or nil."
  []
  (when-let [r (one "SELECT host, token_hash, paired_at, seen_at FROM release_builder WHERE id = 1")]
    {:host (:host r) :token-hash (:token_hash r) :paired-at (:paired_at r) :seen-at (:seen_at r)}))

(defn save-release-builder!
  "Makes host the release builder (the one before it stops counting at once)."
  [host token-hash]
  (exec! "INSERT INTO release_builder (id, host, token_hash, paired_at) VALUES (1, ?, ?, ?)
         ON CONFLICT (id) DO UPDATE SET host = excluded.host, token_hash = excluded.token_hash,
           paired_at = excluded.paired_at, seen_at = NULL"
         host token-hash (now)))

(defn release-builder-seen! [] (exec! "UPDATE release_builder SET seen_at = ? WHERE id = 1" (now)))

;; System labels (README decision 96) ------------------------------------------------------------

(defn system-labels []
  (all "SELECT id, server, kind, match, project, by, at FROM system_labels ORDER BY server, kind, match"))

(defn save-system-label!
  "Adds a label, or replaces the one with the same server, kind and match; the label."
  [{label-server :server :keys [kind match project by]}]
  (exec! "INSERT INTO system_labels (server, kind, match, project, by, at) VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (server, kind, match) DO UPDATE SET project = excluded.project, by = excluded.by, at = excluded.at"
         label-server kind match project by (now))
  (one "SELECT id, server, kind, match, project, by, at FROM system_labels WHERE server = ? AND kind = ? AND match = ?"
       label-server kind match))

(defn drop-system-label! [id] (exec! "DELETE FROM system_labels WHERE id = ?" id))
