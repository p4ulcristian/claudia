(ns claudia.hub.pairing
  "A server gets its hub key (README decision 47) by pairing. On the server, deploy/pair.sh makes a
  one-time secret and prints a key:

    claudia-pair:<host name>:<secret>

  pasted into the hub's Servers page with the address the hub reaches that server's front at (on
  whatever private network joins them). The server polls POST /hub/enroll with its secret and gets
  its name and hub key once. Pairing a server the hub already knows (same name) gives it its key
  again, keeping its address unless a new one is given."
  (:require [claudia.node.crypto :refer [sha256-hex]]
            [claudia.shared.text :as text]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.hub-key :as hub-key]
            [claudia.hub.store :as store]))

(def ^:private secret-re #"^[A-Za-z0-9_-]{43}$")

(defn parse-key
  "A pasted pairing key -> {:host :secret}, or nil."
  [raw]
  (let [parts (str/split (str/trim (str raw)) #":")]
    (when (and (= 3 (count parts)) (= "claudia-pair" (first parts)))
      (let [[_ host secret] parts]
        (when (re-matches secret-re secret)
          {:host (text/cut host 64 "") :secret secret})))))

(defn suggest-name
  "The host name a key carries, as a server name."
  [host]
  (let [n (-> (str/lower-case host)
              (str/replace #"[^a-z0-9-]+" "-")
              (str/replace #"^[^a-z]+" ""))]
    (if (str/blank? n) "server" (text/cut n 31 ""))))

(defn pair-problem
  "What is wrong with pairing this server, or nil. old: the server the hub already has by that name."
  [server-name address old]
  (cond
    (not (re-matches config/server-name server-name))
    "A server name: lower-case letters, digits and dashes, starting with a letter."
    (and (str/blank? address) (not old))
    "Address: where the hub reaches this server's front, host:port (e.g. 100.101.2.3:4540)."
    (and (seq address) (not (config/host-port address)))
    "Address: host:port, e.g. 100.101.2.3:4540."
    :else nil))

(defn pair!
  "Adds (or keeps) the server and records its secret; throws with a message the admin reads."
  [{:keys [secret]} server-name label address]
  (let [old (store/server server-name)]
    (when-let [problem (pair-problem server-name address old)]
      (throw (js/Error. problem)))
    (when (store/pairing-by-secret (sha256-hex secret))
      (throw (js/Error. "This key was already used.")))
    (store/add-pairing! {:name server-name
                         :label (or (not-empty label) (:label old) server-name)
                         :address (or (not-empty address) (:address old))}
                        (sha256-hex secret))
    {:name server-name}))

(defn enroll
  "A joining server asks with its secret -> [status body]: 200 {:name :key} once; 404 before the
  admin pasted its key, or after it fetched it (pair again for a new fetch)."
  [secret]
  (if-not (and (string? secret) (re-matches secret-re secret))
    [400 {:error "secret"}]
    (let [p (store/pairing-by-secret (sha256-hex secret))]
      (if (or (not p) (:fetchedAt p))
        [404 {:error "no pairing for this secret"}]
        (do (store/mark-fetched! (:server p))
            [200 {:name (:server p) :key (hub-key/for-server (:server p))}])))))
