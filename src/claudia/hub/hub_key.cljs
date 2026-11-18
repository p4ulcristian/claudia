(ns claudia.hub.hub-key
  "How the hub proves itself to a server's front (README decision 47): every call it makes carries
  X-Claudia-Hub-Key, that server's key (claudia.node.crypto/server-key) under the hub's secret in
  HUB_KEY_FILE. A front answers no call without its key, so reaching its port is not enough. The
  same key proves a server to the hub (server-caller: Claudia's chat, SSH certificates)."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.hub.store :as store]
            [claudia.node.crypto :as crypto]
            [claudia.node.env :refer [env]]))

(defonce ^:private secret (atom nil))

(def min-secret-length "The fewest characters a hub secret has (32 random bytes as base64: 44)." 40)

(defn use-secret!
  "Keeps text as the hub's secret; throws when it is too short to be one."
  [text]
  (when-not (and (string? text) (>= (count text) min-secret-length))
    (throw (js/Error. (str "HUB_KEY_FILE must name a file holding the hub's secret (at least "
                           min-secret-length " characters, e.g. `openssl rand -base64 32`)."))))
  (reset! secret text))

(defn start!
  "Reads HUB_KEY_FILE; a hub without one stops: it could reach no front."
  []
  (let [file (env "HUB_KEY_FILE")]
    (use-secret! (when file (str/trim (fs/readFileSync file "utf8"))))))

(defn for-server
  "The key of the server with that name (what its front keeps in FRONT_HUB_KEY_FILE)."
  [server-name]
  (crypto/server-key @secret server-name))

(defn header
  "The hub's proof for that server's front, as a header map."
  [server-name]
  {"x-claudia-hub-key" (for-server server-name)})

(defn server-caller
  "Which server a server's call to the hub comes from (X-Claudia-Server-Name, X-Claudia-Server-Key):
  {:server-name :key-ok?}; key-ok? only when that server is known and the key is its own, compared
  in constant time."
  [^js req]
  (let [h (.-headers req)
        server-name (str (aget h "x-claudia-server-name"))]
    {:server-name server-name
     :key-ok? (boolean (and (seq server-name) (store/server server-name)
                            (crypto/same-secret? (aget h "x-claudia-server-key") (for-server server-name))))}))
