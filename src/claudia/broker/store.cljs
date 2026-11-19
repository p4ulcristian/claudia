(ns claudia.broker.store
  "The key broker's own store (node:sqlite, BROKER_DATA/broker.db; README decision 66): each
  person's passkeys (public keys only) and the one-time enrollment codes made on the gate. The hub
  never opens this file: it runs as another user and has no access to the folder."
  (:require ["node:sqlite" :refer [DatabaseSync]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defonce ^:private db (atom nil))

(def ^:private schema
  "PRAGMA journal_mode = WAL;
   -- A passkey: the credential id (base64url) WebAuthn names it by, whose it is (the email the
   -- hub vouched for when it was added), the name the person gave it, its COSE public key
   -- (base64url), the signature counter, its transports (JSON array) and when it was added and
   -- last used (ms).
   CREATE TABLE IF NOT EXISTS passkeys (
     id TEXT PRIMARY KEY,
     email TEXT NOT NULL,
     name TEXT NOT NULL,
     public_key TEXT NOT NULL,
     counter INTEGER NOT NULL,
     transports TEXT NOT NULL,
     created_at INTEGER NOT NULL,
     used_at INTEGER
   );
   CREATE INDEX IF NOT EXISTS passkeys_email ON passkeys(email);
   -- A one-time enrollment code (`broker.js enroll <email>` on the gate): its SHA-256 (hex), when
   -- it ends (ms) and the wrong tries so far. One per person; a new one replaces the old.
   CREATE TABLE IF NOT EXISTS enroll_codes (
     email TEXT PRIMARY KEY,
     code_hash TEXT NOT NULL,
     expires INTEGER NOT NULL,
     tries INTEGER NOT NULL
   );
   -- Broker admins (`broker.js admin <email>` on the gate): they may use every key and tick who
   -- else may use which. Kept here, not taken from the hub, so a hacked hub cannot make anyone
   -- an admin.
   CREATE TABLE IF NOT EXISTS admins (email TEXT PRIMARY KEY);
   -- Who may use which key: a 1Password item id and a person. No row = no access (admins aside).
   CREATE TABLE IF NOT EXISTS grants (
     item TEXT NOT NULL,
     email TEXT NOT NULL,
     PRIMARY KEY (item, email)
   );")

(defn open!
  "Opens (and makes) dir/broker.db."
  [dir]
  (fs/mkdirSync dir #js {:recursive true})
  (let [d (DatabaseSync. (path/join dir "broker.db"))]
    (.exec d schema)
    (reset! db d)))

(defn close! [] (some-> ^js @db .close) (reset! db nil))

(defn- stmt ^js [sql] (.prepare ^js @db sql))

(defn- exec! [sql & args]
  (let [^js s (stmt sql)]
    (.-changes (.apply (.-run s) s (into-array args)))))

(defn- ->map [^js r]
  (when r (js->clj (js/Object.assign #js {} r) :keywordize-keys true)))

(defn- all [sql & args]
  (let [^js s (stmt sql)]
    (mapv ->map (.apply (.-all s) s (into-array args)))))

(defn- one [sql & args]
  (let [^js s (stmt sql)]
    (->map (.apply (.-get s) s (into-array args)))))

(defn- passkey-row [r]
  (when r
    {:id (:id r) :email (:email r) :name (:name r) :publicKey (:public_key r) :counter (:counter r)
     :transports (vec (js->clj (js/JSON.parse (:transports r)))) :createdAt (:created_at r) :usedAt (:used_at r)}))

(defn passkeys-of
  "A person's passkeys, oldest first."
  [email]
  (mapv passkey-row (all "SELECT * FROM passkeys WHERE email = ? ORDER BY created_at" email)))

(defn passkey
  "One passkey by its credential id, or nil."
  [id]
  (passkey-row (one "SELECT * FROM passkeys WHERE id = ?" id)))

(defn add-passkey!
  "Keeps a verified passkey."
  [{:keys [id email publicKey counter transports] passkey-name :name} now]
  (exec! "INSERT INTO passkeys (id, email, name, public_key, counter, transports, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
         id email passkey-name publicKey counter (js/JSON.stringify (clj->js (vec transports))) now))

(defn used-passkey!
  "A passkey just signed: its new counter and when."
  [id counter now]
  (exec! "UPDATE passkeys SET counter = ?, used_at = ? WHERE id = ?" counter now id))

(defn remove-passkey!
  "Drops one of a person's passkeys; 1 when it was theirs, else 0."
  [email id]
  (exec! "DELETE FROM passkeys WHERE email = ? AND id = ?" email id))

(defn put-code!
  "Keeps a person's enrollment code (its hash), replacing any older one."
  [email code-hash expires]
  (exec! "INSERT INTO enroll_codes (email, code_hash, expires, tries) VALUES (?, ?, ?, 0)
          ON CONFLICT(email) DO UPDATE SET code_hash = excluded.code_hash, expires = excluded.expires, tries = 0"
         email code-hash expires))

(defn code-of
  "A person's enrollment code row {:hash :expires :tries}, or nil."
  [email]
  (when-let [r (one "SELECT * FROM enroll_codes WHERE email = ?" email)]
    {:hash (:code_hash r) :expires (:expires r) :tries (:tries r)}))

(defn wrong-code! [email] (exec! "UPDATE enroll_codes SET tries = tries + 1 WHERE email = ?" email))

(defn drop-code! [email] (exec! "DELETE FROM enroll_codes WHERE email = ?" email))

(defn admin? [email] (some? (one "SELECT email FROM admins WHERE email = ?" email)))

(defn add-admin! [email] (exec! "INSERT OR IGNORE INTO admins (email) VALUES (?)" email))

(defn admins [] (mapv :email (all "SELECT email FROM admins ORDER BY email")))

(defn grants
  "Every tick: [{:item :email}]."
  []
  (all "SELECT item, email FROM grants ORDER BY item, email"))

(defn granted-items
  "The item ids a person may use."
  [email]
  (mapv :item (all "SELECT item FROM grants WHERE email = ?" email)))

(defn set-grant!
  "One tick on or off."
  [{:keys [item email on]}]
  (if on
    (exec! "INSERT OR IGNORE INTO grants (item, email) VALUES (?, ?)" item email)
    (exec! "DELETE FROM grants WHERE item = ? AND email = ?" item email)))
