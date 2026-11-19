(ns claudia.hub.github-services
  "GitHub for services that are no person (README decision 78): the hub makes each one 1-hour
  installation tokens of its GitHub App and hands them to that service on its server, so no server
  keeps a long-lived GitHub secret. The app's id and private key come from the gate's secrets
  (HUB_GITHUB_APP_ID, HUB_GITHUB_APP_PRIVATE_KEY_FILE); which services get tokens, on which server
  and for which repositories, from HUB_GITHUB_APP_SERVICES (parse-services). Every minute a token
  running out within 15 minutes is replaced, and a token the service's front does not have yet is
  handed there (PUT /api/github/service-token, a call only the hub makes; claudia.slave.github-services
  writes it where the service reads it). What the service does on GitHub shows as the app's bot."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["jose" :as jose]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.front :as front]
            [claudia.hub.github-app :as github-app]
            [claudia.node.env :refer [env]]))

(def ^:private api "https://api.github.com")
(def ^:private renew-ms "A token running out within this is replaced." (* 15 60 1000))

;; ---- pure ----

(def ^:private service-re #"^([a-z][a-z0-9-]{0,31})@([A-Za-z0-9][A-Za-z0-9._-]{0,62})=([^|]+)\|(.+)$")
(def ^:private repo-re #"^([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})$")
(def ^:private permission-re #"^([a-z_]+):(read|write)$")

(defn- parse-permissions [text]
  (let [pairs (map #(re-matches permission-re %) (str/split text #","))]
    (when (every? some? pairs) (into {} (map (fn [[_ k v]] [(keyword k) v])) pairs))))

(defn- parse-service [entry]
  (when-let [[_ svc server repos perms] (re-matches service-re entry)]
    (let [pairs (map #(re-matches repo-re %) (str/split repos #","))
          owners (set (map second pairs))
          permissions (parse-permissions perms)]
      (when (and (every? some? pairs) (= 1 (count owners)) (seq permissions))
        {:name svc :server server :owner (first owners) :repos (mapv #(nth % 2) pairs)
         :permissions permissions}))))

(defn parse-services
  "HUB_GITHUB_APP_SERVICES: entries `<service>@<server>=<owner>/<repo>,…|<permission>:<read|write>,…`
  split by spaces or semicolons; one entry's repositories all of one owner (one installation of the
  app), its permissions those its tokens get (never more than the app has). {:services [{:name
  :server :owner :repos :permissions}] :bad [entries not understood]}."
  [text]
  (let [entries (remove str/blank? (str/split (or text "") #"[\s;]+"))
        parsed (map (juxt identity parse-service) entries)]
    {:services (vec (keep second parsed))
     :bad (vec (keep (fn [[e s]] (when-not s e)) parsed))}))

(defn due?
  "Whether a held token ({:token :expires-at} or nil) is to be replaced at now (ms)."
  [held now]
  (or (nil? held) (< (- (:expires-at held) now) renew-ms)))

(defn token-body
  "What the front is handed for a service: its name, token, expiry (ms) and the bot it acts as."
  [svc {:keys [token expires-at]} slug]
  {:name (:name svc) :token token :expiresAt expires-at :login (str slug "[bot]")})

;; ---- the app's settings ----

(defn settings
  "{:app-id :private-key (a KeyObject) :slug :services} when the hub has the app's id, private key and slug
  and at least one service; nil otherwise."
  []
  (let [id (env "HUB_GITHUB_APP_ID" "")
        pem (some-> (env "HUB_GITHUB_APP_PRIVATE_KEY_FILE" nil) (#(try (fs/readFileSync % "utf8") (catch :default _ nil))))
        slug (env "HUB_GITHUB_APP_SLUG" "")
        {:keys [services bad]} (parse-services (env "HUB_GITHUB_APP_SERVICES" ""))]
    (doseq [b bad] (js/console.error "github-services: not understood in HUB_GITHUB_APP_SERVICES:" b))
    (when (and (re-matches #"^\d+$" id) (seq pem) (seq slug) (seq services))
      {:app-id id :private-key (crypto/createPrivateKey pem) :slug slug :services services})))

;; ---- GitHub ----

(defn- app-jwt
  "Promise of a JWT that proves the hub is the app (10 minutes, issued a minute back for clock skew)."
  [{:keys [app-id private-key]}]
  (let [now (js/Math.floor (/ (js/Date.now) 1000))]
    (-> (jose/SignJWT. #js {})
        (.setProtectedHeader #js {:alg "RS256"})
        (.setIssuedAt (- now 60))
        (.setExpirationTime (+ now 540))
        (.setIssuer app-id)
        (.sign private-key))))

(defn- as-app [jwt]
  {"authorization" (str "Bearer " jwt) "accept" "application/vnd.github+json" "x-github-api-version" "2022-11-28"})

(defn- mint
  "Promise of a fresh token {:token :expires-at} for svc, or nil (said in the log). The installation
  is the one on the owner of svc's first repository; the token reaches svc's repositories, with svc's permissions, only."
  [s svc]
  (-> (app-jwt s)
      (.then (fn [jwt]
               (-> (github-app/github-json "GET" (str api "/repos/" (:owner svc) "/" (first (:repos svc)) "/installation")
                                           (as-app jwt) nil)
                   (.then (fn [[status body]]
                            (if (and (= 200 status) (:id body))
                              (github-app/github-json "POST" (str api "/app/installations/" (:id body) "/access_tokens")
                                                      (as-app jwt) {:repositories (:repos svc) :permissions (:permissions svc)})
                              [status {:message (str "the app is not installed on " (:owner svc))}]))))))
      (.then (fn [[status body]]
               (if (and (= 201 status) (:token body))
                 {:token (:token body) :expires-at (.getTime (js/Date. (:expires_at body)))}
                 (js/console.error "github-services: no token for" (:name svc) "-" status (:message body)))))
      (.catch (fn [e] (js/console.error "github-services: no token for" (:name svc) "-" (.-message e))))))

;; ---- holding and handing out ----

;; service name -> {:token :expires-at}; service name -> sha-256 of the token its front has.
(defonce ^:private held (atom {}))
(defonce ^:private handed (atom {}))

(defn- fresh
  "Promise of svc's current token, minted first when due."
  [s svc]
  (let [h (get @held (:name svc))]
    (if-not (due? h (js/Date.now))
      (js/Promise.resolve h)
      (.then (mint s svc) (fn [t] (if t (do (swap! held assoc (:name svc) t) t) h))))))

(defn- hand!
  "Gives svc's token to its server's front, when that front does not have this one yet."
  [s svc t]
  (let [server (config/server (:server svc)) d (github-app/digest (:token t))]
    (cond
      (not (:host server)) (js/console.error "github-services: no server" (:server svc) "for" (:name svc))
      (not= d (get @handed (:name svc)))
      (.then (front/call server "PUT" "/api/github/service-token" (token-body svc t (:slug s)) 20000)
             (fn [[status body]]
               (if (= 200 status)
                 (swap! handed assoc (:name svc) d)
                 (js/console.error "github-services:" (:name svc) "on" (:server svc) "-" status (:error body))))))))

(defn- sync-service! [s svc]
  (.then (fresh s svc) #(when (:token %) (hand! s svc %))))

(defonce ^:private running (atom nil))

(defn sync!
  "Promise: every service's token fresh and on its front (one run at a time)."
  [s]
  (or @running
      (let [p (.finally (js/Promise.all (into-array (map #(sync-service! s %) (:services s))))
                        #(reset! running nil))]
        (reset! running p)
        p)))

(defn start!
  "Every minute, once the hub has the app's id, key and services."
  []
  (when-let [s (settings)]
    (js/console.log "github-services:" (str/join ", " (map #(str (:name %) "@" (:server %)) (:services s))))
    (js/setTimeout #(sync! s) 5000)
    (js/setInterval #(sync! s) 60000)))
