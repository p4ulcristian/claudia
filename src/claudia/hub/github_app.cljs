(ns claudia.hub.github-app
  "Each person's GitHub through the hub's GitHub App (README decision 37): the app's client id and
  secret come from the gate's secrets (HUB_GITHUB_APP_SLUG, HUB_GITHUB_APP_CLIENT_ID,
  HUB_GITHUB_APP_CLIENT_SECRET_FILE; the app is made once with bin/github-app-create.mjs). A person
  presses Connect (GET /hub/github/connect): GitHub asks them to authorize the app and sends them
  back (GET /hub/github/callback); the hub trades the code for a user token (8 hours) and a refresh
  token (6 months), both sealed in its store under HUB_TOKEN_KEY_FILE. Every minute it renews a
  token that runs out within 30 minutes and hands each person's current token to their profile on
  every server they may use (PUT /api/github through the front, as them: gh, git's helper). No
  server keeps more than that short token. Without the app's settings, the older kept GitHub
  logins (claudia.hub.github-login) go on as before."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.front :as front]
            [claudia.hub.store :as store]
            [claudia.hub.tokens :as tokens]
            [claudia.node.env :refer [env]]
            [claudia.node.http :as io]))

(def ^:private api "https://api.github.com")
(def ^:private renew-ms "A user token running out within this is renewed." (* 30 60 1000))
(def ^:private state-ms "How long a Connect may take on GitHub's pages." (* 15 60 1000))

;; state -> {:email :at}: the Connects on their way through GitHub (in memory: a restart asks again).
(defonce ^:private pending (atom {}))
;; [server email] -> sha-256 of the token last handed there.
(defonce ^:private handed (atom {}))

;; ---- pure ----

(defn settings
  "The app's settings from the environment (k -> value) and the secret file's text, or nil."
  [env-of secret]
  (let [slug (env-of "HUB_GITHUB_APP_SLUG") client (env-of "HUB_GITHUB_APP_CLIENT_ID")]
    (when (and (seq slug) (seq client) (seq secret))
      {:slug slug :client-id client :secret (str/trim secret)})))

(defn authorize-url
  "GitHub's page that asks the person to authorize the app, back to redirect with state."
  [client-id redirect state]
  (str "https://github.com/login/oauth/authorize?client_id=" (js/encodeURIComponent client-id)
       "&redirect_uri=" (js/encodeURIComponent redirect) "&state=" (js/encodeURIComponent state)))

(defn token-record
  "What GitHub's token answer (access_token, expires_in, refresh_token, refresh_token_expires_in;
  seconds) means at now (ms): {:access :access-expires-at :refresh :refresh-expires-at}; the
  expiries are nil for a token that does not run out. nil without a token."
  [answer now]
  (let [at (fn [k] (when (number? (get answer k)) (+ now (* 1000 (get answer k)))))]
    (when (seq (:access_token answer))
      {:access (:access_token answer) :access-expires-at (at :expires_in)
       :refresh (not-empty (:refresh_token answer)) :refresh-expires-at (at :refresh_token_expires_in)})))

(defn renew?
  "Whether a person's user token is renewed at now: it runs out within renew-ms and a refresh token
  that has not run out is kept."
  [{:keys [access-expires-at refresh refresh-expires-at]} now]
  (boolean (and refresh (number? access-expires-at) (< (- access-expires-at now) renew-ms)
                (or (nil? refresh-expires-at) (> refresh-expires-at now)))))

(defn installation-row
  "An installation as the page lists it."
  [i]
  {:id (:id i) :account (get-in i [:account :login]) :type (:target_type i)
   :selection (:repository_selection i) :configureUrl (:html_url i)})

(defn return-path?
  "The hub's pages GitHub sends a browser back to (a cross-site navigation by nature): let through."
  [pathname]
  (contains? #{"/hub/github/callback" "/hub/github/installed"} pathname))

(defn digest [text] (.digest (.update (crypto/createHash "sha256") (str text)) "hex"))

;; ---- the app's settings and a person's tokens ----

(defn app
  "The app's settings, or nil when the hub has none (or keeps no tokens)."
  []
  (when (tokens/on?)
    (settings env (some-> (env "HUB_GITHUB_APP_CLIENT_SECRET_FILE")
                          (#(try (fs/readFileSync % "utf8") (catch :default _ nil)))))))

(defn- aad [email part] (str "github-app:" part ":" email))

(defn- opened [email part sealed]
  (when sealed (tokens/unseal (tokens/sealing-key) (aad email part) sealed)))

(defn person-tokens
  "A person's tokens, opened, with their login and expiries; nil when they never connected."
  [email]
  (when-let [r (store/github-user email)]
    {:email email :login (:login r)
     :access (opened email "access" (:sealed_access r)) :access-expires-at (:access_expires_at r)
     :refresh (opened email "refresh" (:sealed_refresh r)) :refresh-expires-at (:refresh_expires_at r)}))

(defn- keep-tokens!
  "Seals and keeps a person's token record (token-record) under login."
  [email login {:keys [access access-expires-at refresh refresh-expires-at]}]
  (store/save-github-user! {:email email :login login
                            :sealed-access (tokens/seal (tokens/sealing-key) (aad email "access") access)
                            :access-expires-at access-expires-at
                            :sealed-refresh (when refresh (tokens/seal (tokens/sealing-key) (aad email "refresh") refresh))
                            :refresh-expires-at refresh-expires-at}))

;; ---- GitHub ----

(defn github-json
  "Promise of [status body-map] of a call to GitHub (url: absolute; body-map {} unless an object)."
  [method url headers body]
  (-> (js/fetch url (clj->js (cond-> {:method method
                                      :headers (merge {"accept" "application/json" "user-agent" "claudia-hub"} headers)
                                      :signal (js/AbortSignal.timeout 20000)}
                               body (assoc :body (js/JSON.stringify (clj->js body))
                                           :headers (merge {"accept" "application/json" "user-agent" "claudia-hub"
                                                            "content-type" "application/json"} headers)))))
      (.then (fn [^js r] (.then (.text r) (fn [t] [(.-status r) (io/parse-object t)]))))))

(defn- token-call
  "Promise of the token record GitHub gives for a code or a refresh token (grant), or nil."
  [{:keys [client-id secret]} grant]
  (-> (github-json "POST" "https://github.com/login/oauth/access_token" {}
                   (merge {:client_id client-id :client_secret secret} grant))
      (.then (fn [[status body]] (when (= 200 status) (token-record body (js/Date.now)))))))

(defn- as-user [token] {"authorization" (str "Bearer " token) "accept" "application/vnd.github+json"})

(defn- login-of
  "Promise of the GitHub login a user token belongs to, or nil."
  [token]
  (.then (github-json "GET" (str api "/user") (as-user token) nil)
         (fn [[status body]] (when (= 200 status) (:login body)))))

(defn installations
  "Promise of the app's installations a person's token reaches ([] when none or on failure)."
  [token]
  (.then (github-json "GET" (str api "/user/installations?per_page=100") (as-user token) nil)
         (fn [[status body]] (if (= 200 status) (mapv installation-row (:installations body)) []))))

;; ---- handing out ----

(defn- hand!
  "Gives the person's token to their profile on server, when it differs from the last one given."
  [server email token]
  (let [k [(:name server) email] d (digest token)]
    (when (not= d (get @handed k))
      (-> (front/call-as server email "PUT" "/api/github" {:token token} 20000)
          (.then (fn [[status]] (when (= 200 status) (swap! handed assoc k d))))))))

(defn- renewed
  "Promise of the person's tokens, renewed first when they run out soon (renew?)."
  [{:keys [email login refresh] :as t}]
  (if-let [a (when (renew? t (js/Date.now)) (app))]
    (.then (token-call a {:grant_type "refresh_token" :refresh_token refresh})
           (fn [fresh] (if fresh (do (keep-tokens! email login fresh) (person-tokens email)) t)))
    (js/Promise.resolve t)))

(defn- sync-person! [email]
  (when-let [t (person-tokens email)]
    (.then (renewed t)
           (fn [{:keys [access]}]
             (js/Promise.all (into-array (keep #(hand! % email access) (config/servers-of {:email email}))))))))

(defonce ^:private running (atom nil))

(defn sync!
  "Promise: every connected person's token renewed when due and on all their servers (one run at a time)."
  []
  (or @running
      (let [p (.finally (js/Promise.all (into-array (keep #(sync-person! (:email %)) (store/github-users))))
                        #(reset! running nil))]
        (reset! running p)
        p)))

(defn start!
  "Every minute, once the hub has the app's settings."
  []
  (when (app)
    (js/setTimeout sync! 5000)
    (js/setInterval sync! 60000)))

;; ---- Connect, and GitHub sending the browser back ----

(defn- base [] (str/replace (env "HUB_PUBLIC_URL" "") #"/+$" ""))

(defn- redirect! [^js res url]
  (.writeHead res 302 #js {"location" url "cache-control" "no-store"})
  (.end res))

(defn- connect! [res email]
  (let [{:keys [client-id]} (app)
        state (.toString (crypto/randomBytes 16) "hex")]
    (swap! pending #(assoc (into {} (filter (fn [[_ v]] (< (- (js/Date.now) (:at v)) state-ms)) %))
                           state {:email email :at (js/Date.now)}))
    (redirect! res (authorize-url client-id (str (base) "/hub/github/callback") state))))

(defn- connected!
  "After the code turned into tokens: kept, the older kept login dropped, the servers given the token;
  then the person goes on to install the app when it is nowhere yet, else back to Claudia."
  [res email a t]
  (-> (login-of (:access t))
      (.then (fn [login]
               (if-not login
                 (io/send-json res 502 {:error "GitHub did not say whose token this is."})
                 (do (keep-tokens! email login t)
                     (store/drop-github-login! email)
                     (sync-person! email)
                     (.then (installations (:access t))
                            #(redirect! res (if (seq %) (str (base) "/")
                                              (str "https://github.com/apps/" (:slug a) "/installations/new"))))))))))

(defn- callback! [^js res email q]
  (let [state (.get q "state") p (get @pending state) a (app)]
    (swap! pending dissoc state)
    (if-not (and p (= email (:email p)) a)
      (io/send-json res 400 {:error "This GitHub answer is not for a Connect of yours: press Connect again."})
      (.then (token-call a {:code (.get q "code") :redirect_uri (str (base) "/hub/github/callback")})
             #(if % (connected! res email a %)
                  (io/send-json res 502 {:error "GitHub did not give a token for that code."}))))))

;; ---- what the page sees and does ----

(defn state-of
  "For the page: the app (or nil), the person's connection, installations and servers."
  [a t installs servers]
  {:app (when a {:slug (:slug a) :installUrl (str "https://github.com/apps/" (:slug a) "/installations/new")})
   :user (when t {:login (:login t) :accessExpiresAt (:access-expires-at t) :refreshExpiresAt (:refresh-expires-at t)})
   :installations (vec installs)
   :servers (vec servers)})

(defn- state! [res email]
  (let [a (app) t (when a (person-tokens email))
        servers (for [s (config/servers-of {:email email})]
                  {:name (:name s) :label (:label s)
                   :ok (boolean (and t (= (get @handed [(:name s) email]) (digest (:access t)))))})]
    (.then (if t (installations (:access t)) (js/Promise.resolve []))
           #(io/send-json res 200 (state-of a t % servers)))))

(defn- disconnect!
  "Revokes the person's authorization at GitHub and forgets their tokens (their servers keep the
  last short token until it runs out)."
  [res email]
  (let [{:keys [client-id secret]} (app) t (person-tokens email)
        basic (.toString (js/Buffer.from (str client-id ":" secret)) "base64")]
    (-> (if t
          (github-json "DELETE" (str api "/applications/" client-id "/grant")
                       {"authorization" (str "Basic " basic) "accept" "application/vnd.github+json"} {:access_token (:access t)})
          (js/Promise.resolve nil))
        (.then (fn [_] (store/drop-github-user! email) (io/send-json res 200 {:ok true}))))))

(defn call
  "/hub/github/… for a signed-in caller: connect, callback, installed (GET: browser pages), and
  the page's state (GET /hub/github/state) and DELETE /hub/github/connection."
  [^js req ^js res pathname email]
  (let [[_ qs] (str/split (.-url req) #"\?" 2)
        q (js/URLSearchParams. (or qs ""))
        m (.-method req)]
    (cond
      (and (= m "GET") (= pathname "/hub/github/state")) (state! res email)
      (not (app)) (io/send-json res 501 {:error "No GitHub App on this hub."})
      (and (= m "GET") (= pathname "/hub/github/connect")) (connect! res email)
      (and (= m "GET") (= pathname "/hub/github/callback")) (callback! res email q)
      (and (= m "GET") (= pathname "/hub/github/installed")) (redirect! res (str (base) "/"))
      (and (= m "DELETE") (= pathname "/hub/github/connection")) (disconnect! res email)
      :else (io/send-json res 404 {:error "not found"}))))
