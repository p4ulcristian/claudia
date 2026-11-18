(ns claudia.hub.tokens
  "Claude subscriptions, kept by the hub (README decisions 10, 16 and 57): long-term tokens (`claude
  setup-token`) saved once by name, sealed in the store (AES-256-GCM under the 32-byte key in
  HUB_TOKEN_KEY_FILE, which is never in hub.db). Every saved token goes to each server through its
  front (PUT /api/claude-token/saved, a call only the hub makes), so each chat runs on the one picked
  in its header. There is no shared default: each person's last header pick (else the saved token
  named like their profile) goes to each server where they have a profile (PUT
  /api/claude-token/picks), and their chats without a pick run on it. At once after a save, a pick or
  a removal, and every minute for a server that missed it (off, new, reinstalled). The same minute
  the hub collects each server's last heard usage limits per token (GET /api/claude-token/limits, by
  a hash of the token) and keeps the newest for each saved token, for the page's popup of every
  token's limits. Anyone signed in may save or remove one, and pick for themselves; nobody can read
  one back. Without a key file the hub keeps no tokens and leaves the servers alone."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.store :as store]
            [claudia.node.crypto :refer [token-hash]]
            [claudia.node.env :refer [env]]
            [claudia.hub.front :as front]))

(defonce ^:private key-bytes (atom nil))

(defn on? [] (some? @key-bytes))

(defn sealing-key
  "The sealing key (a 32-byte Buffer), nil without HUB_TOKEN_KEY_FILE; GitHub logins are sealed
  under it too (claudia.hub.github-login)."
  []
  @key-bytes)

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
  "Why a pick (or removal) of the token k is refused, or nil: saved = the saved keys."
  [on k saved]
  (cond
    (not on) no-key
    (not (some #{k} saved)) [404 {:error "No saved token by that name."}]
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

;; ---- each person's last pick (README decision 57) ----

(defn effective-pick
  "The token a person's chats without a pick run on: their last header pick ({:key :at}) while that
  token is saved, else the saved token whose key is their profile name (:at nil), else nil."
  [pick saved-keys profile]
  (let [saved? (set saved-keys)]
    (cond (saved? (:key pick)) (select-keys pick [:key :at])
          (and (seq profile) (saved? profile)) {:key profile :at nil}
          :else nil)))

(defn server-picks
  "What a server keeps for its people ([{:email :profile}]): [{:email :key :name :at}] by email, for
  those with a token (picks: {email {:key :at}}; saved: [{:key :name}])."
  [people picks saved]
  (let [names (into {} (map (juxt :key :name)) saved)]
    (vec (sort-by :email
                  (for [{:keys [email profile]} people
                        :let [p (effective-pick (get picks email) (keys names) profile)]
                        :when p]
                    {:email email :key (:key p) :name (names (:key p)) :at (:at p)})))))

(defn same-picks?
  "Whether a server keeps exactly these picks (have: its [{:email :key :name :at}])."
  [have want]
  (= (set (map #(select-keys % [:email :key :name :at]) have)) (set want)))

(defn- sync-picks!
  "Makes one server's picks the hub's: asks which it keeps, writes them all when they differ."
  [server]
  (let [want (server-picks (store/people-on (:name server)) (store/claude-token-picks) (store/claude-tokens))]
    (-> (front/call server "GET" "/api/claude-token/picks" nil 5000)
        (.then (fn [[status have]]
                 (when (and (= 200 status) (not (same-picks? (:picks have) want)))
                   (.then (front/call server "PUT" "/api/claude-token/picks" {:picks want} 5000)
                          (fn [[wrote body]]
                            (if (= 200 wrote)
                              (js/console.log (str "tokens: " (:name server) " keeps " (count want) " people's picks"))
                              (js/console.error (str "tokens: picks to " (:name server) ": " (:error body)))))))))
        (.catch (fn [e] (js/console.error (str "tokens: picks to " (:name server) ": " (.-message e))))))))

;; ---- the servers' fronts ----

(defn saved-rows
  "The saved tokens as a server keeps them, without the tokens: [{:key :name :at}] by key."
  [saved]
  (vec (sort-by :key (for [t saved] {:key (:key t) :name (:name t) :at (:saved-at t)}))))

(defn same-saved?
  "Whether a server keeps exactly the saved tokens (have: its [{:key :name :at}])."
  [have saved]
  (= (vec (sort-by :key (map #(select-keys % [:key :name :at]) have))) (saved-rows saved)))

(defn- write-saved!
  "Promise of [status body]: every saved token written to the server."
  [server saved]
  (front/call server "PUT" "/api/claude-token/saved"
              {:tokens (for [t (saved-rows saved)]
                         (assoc t :token (unseal @key-bytes (:key t) (store/claude-token-sealed (:key t)))))}
              5000))

(defn- sync-saved!
  "Makes one server's saved tokens the hub's: asks which it keeps, writes them all when they differ."
  [server]
  (let [saved (store/claude-tokens)]
    (-> (front/call server "GET" "/api/claude-token/saved" nil 5000)
        (.then (fn [[status have]]
                 (when (and (= 200 status) (not (same-saved? (:tokens have) saved)))
                   (.then (write-saved! server saved)
                          (fn [[wrote _]]
                            (when (= 200 wrote)
                              (js/console.log (str "tokens: " (:name server) " keeps the " (count saved) " saved"))))))))
        (.catch (fn [e] (js/console.error (str "tokens: saved to " (:name server) ": " (.-message e))))))))

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
  (if (on?)
    (js/Promise.all (into-array (mapcat (fn [s] [(.then (sync-saved! s) #(sync-picks! s)) (collect-limits! s)])
                                        (config/all-servers))))
    (js/Promise.resolve nil)))

(defn sync!
  "Promise: every server brought to the hub's saved tokens and picks (one run at a time; a call
  during a run makes one more run after it)."
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
  "For the page: whether tokens are kept here, the person's own pick (name, and when picked; nil
  when it is the token named like their profile), and the saved ones (names, with their last heard
  limits: limits = key -> {:limits :heard-at})."
  [on pick saved limits]
  {:on on
   :pick (when pick {:name (some #(when (= (:key pick) (:key %)) (:name %)) saved) :at (:at pick)})
   :tokens (if on
             (mapv (fn [t] {:name (:name t) :by (:saved-by t) :at (:saved-at t)
                            :limits (get-in limits [(:key t) :limits] [])
                            :heardAt (get-in limits [(:key t) :heard-at])})
                   saved)
             [])})

(defn state
  "The page's view of the tokens for person ({:email})."
  [person]
  (let [on (on?)
        saved (when on (store/claude-tokens))
        pick (when on (effective-pick (get (store/claude-token-picks) (:email person)) (map :key saved)
                                      (:profile (store/person (:email person)))))]
    (state-of on pick saved (when on (store/claude-token-limits)))))

(defn- settled
  "Promise of the state once the servers were brought along, or after 6 s (an off server must not
  hold the answer)."
  [person]
  (-> (js/Promise.race #js [(sync!) (js/Promise. (fn [r] (js/setTimeout r 6000)))])
      (.then (fn [_] (state person)))))

(defn- saved-keys [] (map :key (store/claude-tokens)))

(defn save!
  "Saves (or replaces, by name) a token; every server gets it. Promise of [status body]."
  [person raw-name raw-token]
  (let [n (clean-name raw-name)
        t (clean-token raw-token)]
    (if-let [refusal (save-problem (on?) n t)]
      (js/Promise.resolve refusal)
      (let [k (fold n)]
        (store/transaction! #(do (store/save-claude-token! k n (seal @key-bytes k t) (:email person))
                                 (store/forget-claude-token-limits! k)))
        (js/console.log (str "tokens: \"" n "\" saved by " (:email person)))
        (.then (settled person) (fn [s] [200 s]))))))

(defn pick!
  "The person picked the saved token k in a chat's header (README decision 57): their chats without
  a pick run on it now, on every server. Promise of [status body]."
  [person raw-key]
  (let [k (when (string? raw-key) raw-key)]
    (if-let [refusal (pick-problem (on?) k (when (on?) (saved-keys)))]
      (js/Promise.resolve refusal)
      (do (store/pick-claude-token! (:email person) k)
          (js/console.log (str "tokens: " (:email person) " picked \"" k "\""))
          (.then (settled person) (fn [s] [200 s]))))))

(defn remove!
  "Removes a saved token: chats on it go to their owner's pick. [status body]."
  [person raw-name]
  (let [k (some-> (clean-name raw-name) fold)
        on (on?)]
    (or (pick-problem on k (when on (saved-keys)))
        (do (store/drop-claude-token! k)
            (sync!)
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
