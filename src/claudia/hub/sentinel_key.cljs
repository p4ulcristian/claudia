(ns claudia.hub.sentinel-key
  "Each person's Sentinel API key, kept by the hub (README decision 85) the way it keeps their GitHub
  login (decision 23): one key per person, sealed in the store under the key in HUB_TOKEN_KEY_FILE
  and handed to their profile on every server they may use (PUT /api/sentinel-key through the
  front, as them): at once after a save or a removal, and every minute for a server that missed it
  (off, new, newly allowed). A removed key is remembered as removed, so a server that was off then
  drops its copy later; a person who never set one here is left alone. The page sees only the
  key's first 8 characters. Without a key file the hub keeps no keys and leaves the servers' alone."
  (:require [claudia.hub.config :as config]
            [claudia.hub.front :as front]
            [claudia.hub.store :as store]
            [claudia.hub.tokens :as tokens]
            [claudia.node.crypto :as crypto]
            [claudia.shared.sentinel-key :as sk]))

;; [server name, email] -> {:ok :hint :error :checked}: what the last look found.
(defonce ^:private synced (atom {}))

(def ^:private call-ms "A server takes the key within this, or the call is counted as failed." 20000)

;; ---- pure ----

(defn aad "What a person's sealed key is bound to: it opens only as theirs." [email] (str "sentinel:" email))

(defn push?
  "Whether a server's answer to GET /api/sentinel-key ([status body]) shows another key than the one
  kept (kept: {:fingerprint}, nil fingerprint when removed), so the kept one is handed over."
  [kept [status body]]
  (and (= 200 status) (not= (:fingerprint kept) (:fingerprint body))))

(defn sync-record
  "What a look at a person's profile on a server found, from its last answer [status body], against
  the kept key, at now."
  [kept [status body] now]
  (let [ok (and (= 200 status) (= (:fingerprint kept) (:fingerprint body)))]
    {:ok ok :hint (:hint body)
     :error (when-not ok (or (:error body) (if (= 200 status) "has another key" (str "answered " status))))
     :checked now}))

(defn state-of
  "For the page: whether keys are kept here, the person's hint and when it was saved (kept: {:hint
  :saved-at :removed}), and each of their servers with whether it has it (records: name -> record)."
  [on kept servers records]
  {:on on
   :hint (when-not (:removed kept) (:hint kept))
   :savedAt (when-not (:removed kept) (:saved-at kept))
   :servers (vec (for [s servers
                       :let [r (get records (:name s))]]
                   {:name (:name s) :label (:label s) :ok (boolean (:ok r)) :error (:error r)}))})

(defn save-problem
  "Why a save is refused ([status body]), or nil: on? the hub keeps keys, k the cleaned key."
  [on k]
  (cond
    (not on) [503 {:error "The hub keeps no keys: HUB_TOKEN_KEY_FILE is not set."}]
    (not k) [400 {:error "That doesn't look like a Sentinel key (snt_…). Make one on Sentinel's API keys page."}]
    :else nil))

;; ---- one person on one server ----

(defn- key-of [email]
  (some->> (store/sentinel-key-sealed email) (tokens/unseal (tokens/sealing-key) (aad email))))

(defn- record! [server email kept answer]
  (swap! synced assoc [(:name server) email] (sync-record kept answer (js/Date.now))))

(defn- push!
  "Makes the person's profile on server hold the kept key (or none, when removed): asks, hands it
  over when it differs."
  [server email kept]
  (-> (front/call-as server email "GET" "/api/sentinel-key" nil 5000)
      (.then (fn [answer]
               (if (push? kept answer)
                 (front/call-as server email "PUT" "/api/sentinel-key" {:key (or (key-of email) "")} call-ms)
                 answer)))
      (.then #(record! server email kept %))
      (.catch #(record! server email kept [0 {:error (.-message %)}]))))

(defn- sync-person!
  "The kept key onto the person's servers; nothing for a person who never set one here."
  [email]
  (if-let [kept (store/sentinel-key email)]
    (js/Promise.all (into-array (map #(push! % email kept) (config/servers-of {:email email}))))
    (js/Promise.resolve nil)))

;; ---- every person ----

(defonce ^:private running (atom nil))

(defn sync!
  "Promise: every person's servers brought to their kept key (one run at a time)."
  []
  (or @running
      (let [p (.finally (js/Promise.all (into-array (map #(sync-person! (:email %)) (store/people))))
                        #(reset! running nil))]
        (reset! running p)
        p)))

;; ---- what the page sees and does ----

(defn state
  "The page's view of person's Sentinel key."
  [{:keys [email] :as person}]
  (let [on (tokens/on?)
        servers (config/servers-of person)]
    (state-of on (when on (store/sentinel-key email)) servers
              (into {} (for [s servers] [(:name s) (get @synced [(:name s) email])])))))

(defn- settled
  "Promise of [200 state] once the person's servers took the change, or after 20 s."
  [person]
  (-> (js/Promise.race #js [(sync-person! (:email person)) (js/Promise. (fn [r] (js/setTimeout r call-ms)))])
      (.then (fn [_] [200 (state person)]))))

(defn save!
  "Keeps (or replaces) the person's Sentinel key and hands it to their servers. Promise of [status body]."
  [{:keys [email] :as person} raw]
  (let [k (sk/clean-key raw)]
    (if-let [problem (save-problem (tokens/on?) k)]
      (js/Promise.resolve problem)
      (do (store/save-sentinel-key! email (tokens/seal (tokens/sealing-key) (aad email) k) (sk/hint k) (crypto/token-hash k))
          (js/console.log (str "sentinel: kept " email "'s key " (sk/hint k) "…"))
          (settled person)))))

(defn remove!
  "Drops the person's Sentinel key here and from their servers. Promise of [status body]."
  [{:keys [email] :as person}]
  (if-not (tokens/on?)
    (js/Promise.resolve (save-problem false nil))
    (do (store/save-sentinel-key! email nil nil nil)
        (js/console.log (str "sentinel: removed " email "'s key"))
        (settled person))))

(defn start!
  "Brings every person's servers along now and every minute (only when the hub keeps keys), at the
  same moments as their GitHub login (claudia.hub.github-login)."
  []
  (when (tokens/on?)
    (js/setTimeout sync! 5000)
    (js/setInterval sync! 60000)))
