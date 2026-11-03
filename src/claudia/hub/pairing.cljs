(ns claudia.hub.pairing
  "A new server joins the hub over WireGuard (README decision 9). On the server, deploy/pair.sh makes a
  WireGuard key pair and a one-time secret and prints a key:

    claudia-pair:<host name>:<WireGuard public key>:<secret>

  pasted into the hub's Servers page. The hub gives the server an address from its pool
  (HUB_WG_POOL) and asks the gate for the peer: a file per peer in HUB_PEERS_DIR, one line
  \"<public key> <address>\", applied by the gate's root helper, which answers in HUB_APPLIED_DIR
  (gate.json = the gate's side of the tunnel, <server>.applied = that peer is up). The server polls
  POST /hub/enroll with its secret and gets its address and the gate's details once the peer is up."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.store :as store]
            [claudia.shared.protocol :as protocol]))

(defonce ^:private dirs (atom nil))

(defn- env [k] (let [v (aget js/process.env k)] (when-not (str/blank? v) (str/trim v))))

(defn start!
  "The folders, from the environment (defaults under the hub's data folder)."
  [data-dir]
  (let [peers (or (env "HUB_PEERS_DIR") (path/join data-dir "peers"))
        applied (or (env "HUB_APPLIED_DIR") (path/join data-dir "peers-applied"))]
    (try (fs/mkdirSync peers #js {:recursive true})
         (catch :default e (js/console.error (str "pairing: " peers ": " (.-message e)))))
    (reset! dirs {:peers peers :applied applied})))

(defn server-port
  "Where a paired server's front listens (FRONT_PORT there)."
  []
  (js/parseInt (or (env "HUB_SERVER_PORT") "4540") 10))

(defn available?
  "Pairing needs an address pool; a hub without HUB_WG_POOL shows pairing as unavailable."
  []
  (some? (env "HUB_WG_POOL")))

(defn- pool
  "HUB_WG_POOL, like \"10.0.0.100-10.0.0.199\" (one /24, last octets)."
  []
  (let [[_ prefix from to] (re-matches #"^(\d+\.\d+\.\d+)\.(\d+)-(?:\d+\.\d+\.\d+\.)?(\d+)$" (or (env "HUB_WG_POOL") ""))]
    (when-not prefix
      (throw (js/Error. "This hub has no WireGuard address pool (HUB_WG_POOL), so it can't pair servers.")))
    {:prefix prefix :from (js/parseInt from 10) :to (js/parseInt to 10)}))

(def ^:private wg-key #"^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$")
(def ^:private secret-re #"^[A-Za-z0-9_-]{43}$")

(defn parse-key
  "A pasted pairing key -> {:host :public-key :secret}, or nil."
  [raw]
  (let [parts (str/split (str/trim (str raw)) #":")]
    (when (and (= 4 (count parts)) (= "claudia-pair" (first parts)))
      (let [[_ host public-key secret] parts]
        (when (and (re-matches wg-key public-key) (re-matches secret-re secret))
          {:host (subs host 0 (min 64 (count host))) :public-key public-key :secret secret})))))

(defn- sha256 [s] (-> (crypto/createHash "sha256") (.update s) (.digest "hex")))

(defn suggest-name
  "The host name a key carries, as a server name."
  [host]
  (let [n (-> (str/lower-case host)
              (str/replace #"[^a-z0-9-]+" "-")
              (str/replace #"^[^a-z]+" ""))]
    (if (str/blank? n) "server" (subs n 0 (min 31 (count n))))))

(defn- free-ip []
  (let [{:keys [prefix from to]} (pool)
        used (set (map :ip (store/pairings)))]
    (or (some #(let [ip (str prefix "." %)] (when-not (used ip) ip)) (range from (inc to)))
        (throw (js/Error. "No free WireGuard address left in the pool.")))))

(defn- write-peer!
  "The peer file the gate's helper reads, written beside and renamed: never read half."
  [server public-key ip]
  (let [file (path/join (:peers @dirs) (str server ".peer"))]
    (fs/writeFileSync (str file ".new") (str public-key " " ip "\n") #js {:mode 0644})
    (fs/renameSync (str file ".new") file)))

(defn drop-peer! [server]
  (when (re-matches config/server-name server)
    (fs/rmSync (path/join (:peers @dirs) (str server ".peer")) #js {:force true})))

(defn pair!
  "Adds the server and asks the gate for its peer; throws with a message the admin reads."
  [{:keys [public-key secret]} name label]
  (when-not (re-matches config/server-name name)
    (throw (js/Error. "A server name: lower-case letters, digits and dashes, starting with a letter.")))
  (when (store/server name)
    (throw (js/Error. (str "There is already a server called " name "."))))
  (when (some #(= public-key (:publicKey %)) (store/pairings))
    (throw (js/Error. "This key was already used.")))
  (let [ip (free-ip)]
    (store/add-paired! {:name name :label label :address (str ip ":" (server-port))}
                       {:public-key public-key :ip ip :secret-hash (sha256 secret)})
    (write-peer! name public-key ip)
    {:name name :ip ip}))

(defn applied? [server]
  (fs/existsSync (path/join (:applied @dirs) (str server ".applied"))))

(defn- gate-wg []
  (try (let [g (protocol/read-json (fs/readFileSync (path/join (:applied @dirs) "gate.json") "utf8"))]
         (when (every? string? (map g [:publicKey :endpoint :address])) g))
       (catch :default _ nil)))

(defn enroll
  "A joining server asks with its secret -> [status body]. 404: no such pairing; 202: the peer is not
  up on the gate yet."
  [secret]
  (if-not (and (string? secret) (re-matches secret-re secret))
    [400 {:error "secret"}]
    (if-let [p (store/pairing-by-secret (sha256 secret))]
      (let [gate (gate-wg)]
        (if-not (and gate (applied? (:server p)))
          [202 {:waiting "the gate is adding the peer"}]
          (do (store/mark-fetched! (:server p))
              [200 {:name (:server p) :address (:ip p) :port (server-port)
                    :gate (select-keys gate [:publicKey :endpoint :address])}])))
      [404 {:error "no pairing for this secret"}])))
