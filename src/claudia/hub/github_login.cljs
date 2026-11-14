(ns claudia.hub.github-login
  "Each person's GitHub login, kept by the hub (README decision 23): one token per person, sealed
  in the store under the key in HUB_TOKEN_KEY_FILE (as the Claude tokens are) and handed to their
  profile on every server they may use (PUT /api/github through the front, as them): at once
  after a save, and every minute for a server that missed it (off, new, newly allowed). A person
  with none kept yet keeps the login gh already holds on one of their servers (GET
  /api/github/token, which the hub refuses to pass from a page), so a login made before this is
  not asked for again. Without a key file the hub keeps no logins and leaves the servers' alone."
  (:require [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.front :as front]
            [claudia.hub.store :as store]
            [claudia.hub.tokens :as tokens]))

;; [server name, email] -> {:ok :login :error :checked}: what the last look found.
(defonce ^:private synced (atom {}))

(def ^:private call-ms "gh logs in within this, or the call is counted as failed." 20000)

;; ---- pure ----

(defn clean-token
  "The token without whitespace, when it looks like a GitHub token, else nil."
  [raw]
  (let [t (str/replace (str raw) #"\s+" "")]
    (when (re-matches #"^(github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,}$" t) t)))

(defn aad "What a person's sealed token is bound to: it opens only as theirs." [email] (str "github:" email))

(defn sync-record
  "What a look at a person's profile on a server found, from its answer [status body] at now."
  [[status body] now]
  (let [login (get-in body [:user :login])]
    {:ok (boolean (and (= 200 status) login)) :login login
     :error (when-not (and (= 200 status) login) (or (:error body) (str "answered " status)))
     :checked now}))

(defn state-of
  "For the page: whether logins are kept here, the person's (kept: {:login :came-from :saved-at})
  and each of their servers with what its gh is logged in as (records: server name -> record)."
  [on kept servers records]
  {:on on
   :login (:login kept)
   :savedAt (:saved-at kept)
   :servers (vec (for [s servers
                       :let [r (get records (:name s))]]
                   {:name (:name s) :label (:label s) :ok (boolean (:ok r)) :login (:login r) :error (:error r)}))})

;; ---- one person on one server ----

(defn- token-of [email]
  (some->> (store/github-login-sealed email) (tokens/unseal (tokens/sealing-key) (aad email))))

(defn- record! [server email answer]
  (swap! synced assoc [(:name server) email] (sync-record answer (js/Date.now))))

(defn- push!
  "Makes the person's profile on server logged in as their kept login: asks, hands the token when it differs."
  [server email login]
  (-> (front/call-as server email "GET" "/api/github" nil 5000)
      (.then (fn [[status body :as answer]]
               (if (or (not= 200 status) (= login (get-in body [:user :login])))
                 answer
                 (front/call-as server email "PUT" "/api/github" {:token (token-of email)} call-ms))))
      (.then #(record! server email %))
      (.catch #(record! server email [0 {:error (.-message %)}]))))

(defn- keep!
  "Seals and keeps token as email's login (login), noting where it came from."
  [email login token came-from]
  (store/save-github-login! email login (tokens/seal (tokens/sealing-key) (aad email) token) came-from)
  (js/console.log (str "github: kept " email "'s login @" login " (from " came-from ")")))

(defn- adopt-from!
  "Promise of true when the person's profile on server had a gh login, now kept by the hub."
  [server email]
  (-> (js/Promise.all #js [(front/call-as server email "GET" "/api/github/token" nil 5000)
                           (front/call-as server email "GET" "/api/github" nil 5000)])
      (.then (fn [[[s1 {:keys [token]}] [s2 who]]]
               (let [t (clean-token token) login (get-in who [:user :login])]
                 (boolean (when (and (= 200 s1 s2) t login) (keep! email login t (:name server)) true)))))
      (.catch (fn [_] false))))

;; ---- every person ----

(defn- adopt!
  "Promise: the first login found on the person's servers, in order, kept."
  [email servers]
  (reduce (fn [p s] (.then p #(or % (adopt-from! s email)))) (js/Promise.resolve false) servers))

(defn- sync-person! [email]
  (let [servers (config/servers-of {:email email})]
    (if-let [login (:login (store/github-login email))]
      (js/Promise.all (into-array (map #(push! % email login) servers)))
      (.then (adopt! email servers) (fn [kept?] (when kept? (sync-person! email)))))))

(defonce ^:private running (atom nil))

(defn sync!
  "Promise: every person's servers brought to their kept login (one run at a time)."
  []
  (or @running
      (let [p (.finally (js/Promise.all (into-array (map #(sync-person! (:email %)) (store/people))))
                        #(reset! running nil))]
        (reset! running p)
        p)))

;; ---- what the page sees and does ----

(defn state
  "The page's view of person's GitHub login."
  [{:keys [email] :as person}]
  (let [on (tokens/on?)
        servers (config/servers-of person)]
    (state-of on (when on (store/github-login email)) servers
              (into {} (for [s servers] [(:name s) (get @synced [(:name s) email])])))))

(defn- github-user
  "Promise of the login a token belongs to (GitHub's own answer), or nil."
  [token]
  (-> (js/fetch "https://api.github.com/user"
                #js {:headers #js {"authorization" (str "Bearer " token) "user-agent" "claudia"
                                   "accept" "application/vnd.github+json"}})
      (.then #(when (.-ok %) (.json %)))
      (.then #(some-> % .-login))
      (.catch (fn [_] nil))))

(defn- settled
  "Promise of the state once the person's servers took the login, or after 20 s."
  [person]
  (-> (js/Promise.race #js [(sync-person! (:email person)) (js/Promise. (fn [r] (js/setTimeout r call-ms)))])
      (.then (fn [_] [200 (state person)]))))

(defn save!
  "Keeps (or replaces) the person's GitHub token and hands it to their servers. Promise of [status body]."
  [{:keys [email] :as person} raw]
  (let [t (clean-token raw)]
    (cond
      (not (tokens/on?)) (js/Promise.resolve [503 {:error "The hub keeps no tokens: HUB_TOKEN_KEY_FILE is not set."}])
      (not t) (js/Promise.resolve [400 {:error "That doesn't look like a GitHub token (github_pat_… or ghp_…)."}])
      :else (.then (github-user t)
                   (fn [login]
                     (if-not login
                       [400 {:error "GitHub refused the token."}]
                       (do (keep! email login t "page") (settled person))))))))

(defn start!
  "Brings every person's servers along now and every minute (only when the hub keeps tokens)."
  []
  (when (tokens/on?)
    (js/setTimeout sync! 5000)
    (js/setInterval sync! 60000)))
