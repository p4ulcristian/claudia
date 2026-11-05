(ns claudia.hub.tokens
  "Claude subscriptions, kept by the hub (README decision 10): long-term tokens (`claude setup-token`)
  saved once by name, sealed in the store (AES-256-GCM under the 32-byte key in HUB_TOKEN_KEY_FILE,
  which is never in hub.db), and one of them in use on every server. The hub writes the one in use
  into each server's token file through its front (PUT /api/claude-token/current, a call only the
  hub makes): at once after a save or a switch, and every minute for a server that missed it (off,
  new, reinstalled). The same minute it collects each server's last heard usage limits per token
  (GET /api/claude-token/limits, by a hash of the token) and keeps the newest for each saved token,
  for the page's popup of every token's limits. Anyone signed in may save, switch or remove one; nobody can read one back.
  Without a key file the hub keeps no tokens and leaves the servers' token files alone."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.store :as store]
            [claudia.node.crypto :refer [token-hash]]
            [claudia.node.env :refer [env]]
            [claudia.hub.front :as front]))

(defonce ^:private key-bytes (atom nil))

;; server name -> {:ok :name :at :error :checked}: what the last look at that server found.
(defonce ^:private synced (atom {}))

(defn on? [] (some? @key-bytes))

;; ---- names and tokens (pure) ----

(defn fold
  "A token's key in the store: its name in lower case, every run of other characters one dash."
  [token-name]
  (str/replace (str/lower-case token-name) #"[^a-z0-9]+" "-"))

(defn clean-name
  "The name trimmed, when it is one (letters, digits, spaces, . _ -; up to 24), else nil."
  [raw]
  (let [n (str/trim (str raw))]
    (when (re-matches #"^[A-Za-z0-9][A-Za-z0-9 ._-]{0,23}$" n) n)))

(defn clean-token
  "The token without whitespace, when it looks like a Claude token, else nil."
  [raw]
  (let [t (str/replace (str raw) #"\s+" "")]
    (when (re-matches #"^sk-ant-[A-Za-z0-9_-]{20,}$" t) t)))

(def ^:private no-key
  [503 {:error "The hub keeps no tokens: HUB_TOKEN_KEY_FILE is not set."}])

(defn save-problem
  "Why a save is refused ([status body]), or nil: on? the hub keeps tokens, n and t the cleaned name
  and token."
  [on n t]
  (cond
    (not on) no-key
    (not n) [400 {:error "Give it a name, like Paul (letters, digits, spaces, up to 24)."}]
    (not t) [400 {:error "That doesn't look like a Claude token (sk-ant-…). Make one with `claude setup-token`."}]
    :else nil))

(defn pick-problem
  "Why a switch to (or removal of, remove? true) the token k is refused, or nil: saved = the saved
  keys, in-use = the key in use."
  [on k saved in-use remove?]
  (cond
    (not on) no-key
    (not (some #{k} saved)) [404 {:error "No saved token by that name."}]
    (and remove? (= k in-use)) [409 {:error "That one is in use: switch to another first."}]
    :else nil))

;; ---- sealing ----

(defn seal
  "base64 of nonce (12) || ciphertext || tag (16) under the 32-byte secret; aad (the token's key)
  is bound in, so a sealed token only opens under its own key."
  [secret aad text]
  (let [nonce (crypto/randomBytes 12)
        ^js c (crypto/createCipheriv "aes-256-gcm" secret nonce)]
    (.setAAD c (js/Buffer.from aad "utf8"))
    (let [body (js/Buffer.concat #js [(.update c text "utf8") (.final c)])]
      (.toString (js/Buffer.concat #js [nonce body (.getAuthTag c)]) "base64"))))

(defn unseal
  "The text sealed by seal under the same secret and aad; throws when either differs."
  [secret aad sealed]
  (let [b (js/Buffer.from sealed "base64")
        ^js d (crypto/createDecipheriv "aes-256-gcm" secret (.subarray b 0 12))]
    (.setAAD d (js/Buffer.from aad "utf8"))
    (.setAuthTag d (.subarray b (- (.-length b) 16)))
    (str (.update d (.subarray b 12 (- (.-length b) 16)) js/undefined "utf8") (.final d "utf8"))))

;; ---- the servers' fronts ----

(defn same-token?
  "Whether a server already runs on the token in use (same name and same switch time)."
  [have in-use]
  (and (= (:name have) (:name in-use)) (= (:at have) (:at in-use))))

(defn sync-record
  "What a look at a server found, from its last answer [status body] at time now."
  [[status body] now]
  (let [ok (= 200 status)]
    {:ok ok :name (when ok (:name body)) :at (when ok (:at body))
     :error (when-not ok (or (:error body) (str "answered " status)))
     :checked now}))

(defn- write-token!
  "Promise of [status body]: the token in use written to the server."
  [server in-use]
  (-> (front/call server "PUT" "/api/claude-token/current"
                  {:name (:name in-use) :by (:by in-use) :at (:at in-use)
                   :token (unseal @key-bytes (:key in-use) (store/claude-token-sealed (:key in-use)))}
                  5000)
      (.then (fn [[status body :as answer]]
               (when (= 200 status) (js/console.log (str "tokens: " (:name server) " now on \"" (:name body) "\"")))
               answer))))

(defn- sync-server!
  "Makes one server's token in use the hub's: asks what it has, writes it when that differs."
  [server in-use]
  (-> (front/call server "GET" "/api/claude-token/current" nil 5000)
      (.then (fn [[status have :as answer]]
               (if (or (not= 200 status) (same-token? have in-use)) answer (write-token! server in-use))))
      (.then #(swap! synced assoc (:name server) (sync-record % (js/Date.now))))
      (.catch (fn [e] (swap! synced assoc (:name server) {:ok false :error (.-message e) :checked (js/Date.now)})))))

(defn heard-rows
  "A server's kept limits ({token hash {:limits :at}}) as [key limits at] of saved tokens, by
  key-of (token hash -> saved token's key); entries without limits are left out, a time kept before
  runners kept times is 0 (any heard later wins)."
  [key-of heard]
  (vec (for [[h {:keys [limits at]}] heard
             :let [k (key-of (name h))]
             :when (and k (seq limits))]
         [k limits (if (number? at) at 0)])))

(defn- hash->key
  "Each saved token's hash -> its key."
  []
  (into {} (for [{k :key} (store/claude-tokens)]
             [(token-hash (unseal @key-bytes k (store/claude-token-sealed k))) k])))

(defn- collect-limits!
  "Promise: the server's last heard limits per token kept, when newer than the hub's."
  [server]
  (-> (front/call server "GET" "/api/claude-token/limits" nil 5000)
      (.then (fn [[status body]]
               (when (= 200 status)
                 (doseq [[k limits at] (heard-rows (hash->key) (:tokens body))]
                   (store/heard-claude-token-limits! k limits at)))))
      (.catch (fn [e] (js/console.error (str "tokens: limits from " (:name server) ": " (.-message e)))))))

(defonce ^:private running (atom nil))
(defonce ^:private again (atom false))

(defn- sync-all []
  (if-let [in-use (when (on?) (store/claude-token-use))]
    (js/Promise.all (into-array (mapcat (fn [s] [(sync-server! s in-use) (collect-limits! s)]) (config/all-servers))))
    (js/Promise.resolve nil)))

(defn sync!
  "Promise: every server brought to the token in use (one run at a time; a call during a run makes
  one more run after it)."
  []
  (if-let [p @running]
    (do (reset! again true) p)
    (let [p (.finally (sync-all)
                      (fn []
                        (reset! running nil)
                        (when @again (reset! again false) (sync!))))]
      (reset! running p)
      p)))

;; ---- what the page sees and does ----

(defn state-of
  "For the page: whether tokens are kept here, the one in use, the saved ones (names, with their
  last heard limits: limits = key -> {:limits :heard-at}), and each of the person's servers: what it
  runs on now (synced: server name -> record)."
  [on in-use saved limits servers synced-now]
  {:on on
   :inUse (when in-use (select-keys in-use [:name :by :at]))
   :tokens (if on
             (mapv (fn [t] {:name (:name t) :by (:saved-by t) :at (:saved-at t)
                            :limits (get-in limits [(:key t) :limits] [])
                            :heardAt (get-in limits [(:key t) :heard-at])})
                   saved)
             [])
   :servers (vec (for [s servers
                       :let [st (get synced-now (:name s))]]
                   {:name (:name s) :label (:label s)
                    :ok (boolean (:ok st)) :current (:name st) :error (:error st)}))})

(defn state
  "The page's view of the tokens for person."
  [person]
  (let [on (on?)]
    (state-of on (when on (store/claude-token-use)) (when on (store/claude-tokens))
              (when on (store/claude-token-limits)) (config/servers-of person) @synced)))

(defn- settled
  "Promise of the state once the servers were brought along, or after 6 s (an off server must not
  hold the answer)."
  [person]
  (-> (js/Promise.race #js [(sync!) (js/Promise. (fn [r] (js/setTimeout r 6000)))])
      (.then (fn [_] (state person)))))

(defn- saved-keys [] (map :key (store/claude-tokens)))

(defn save!
  "Saves (or replaces, by name) a token and switches every server to it. Promise of [status body]."
  [person raw-name raw-token]
  (let [n (clean-name raw-name)
        t (clean-token raw-token)]
    (if-let [refusal (save-problem (on?) n t)]
      (js/Promise.resolve refusal)
      (let [k (fold n)]
        (store/transaction! #(do (store/save-claude-token! k n (seal @key-bytes k t) (:email person))
                                 (store/forget-claude-token-limits! k)
                                 (store/use-claude-token! k (:email person))))
        (js/console.log (str "tokens: \"" n "\" saved and in use, by " (:email person)))
        (.then (settled person) (fn [s] [200 s]))))))

(defn use!
  "Switches every server to a saved token. Promise of [status body]."
  [person raw-name]
  (let [k (some-> (clean-name raw-name) fold)]
    (if-let [refusal (pick-problem (on?) k (when (on?) (saved-keys)) nil false)]
      (js/Promise.resolve refusal)
      (do (store/use-claude-token! k (:email person))
          (js/console.log (str "tokens: switched to \"" (:name (store/claude-token-use)) "\" by " (:email person)))
          (.then (settled person) (fn [s] [200 s]))))))

(defn remove!
  "Removes a saved token (not the one in use). [status body]."
  [person raw-name]
  (let [k (some-> (clean-name raw-name) fold)
        on (on?)]
    (or (pick-problem on k (when on (saved-keys)) (when on (:key (store/claude-token-use))) true)
        (do (store/drop-claude-token! k)
            (js/console.log (str "tokens: \"" raw-name "\" removed by " (:email person)))
            [200 (state person)]))))

(defn read-key
  "The key from file f (base64 of 32 bytes) as a Buffer, or nil when it is not one."
  [f]
  (let [k (try (js/Buffer.from (str/trim (fs/readFileSync f "utf8")) "base64") (catch :default _ nil))]
    (when (and k (= 32 (.-length k))) k)))

(defn start!
  "Reads the key and brings the servers along now and every minute."
  []
  (when-let [f (env "HUB_TOKEN_KEY_FILE")]
    (if-let [k (read-key f)]
      (reset! key-bytes k)
      (js/console.error (str "tokens: " f " is not base64 of 32 bytes: the hub keeps no tokens"))))
  (js/console.log (str "tokens: " (if (on?) "kept here" "off (no HUB_TOKEN_KEY_FILE)")))
  (when (on?)
    (js/setTimeout sync! 3000)
    (js/setInterval sync! 60000)))
