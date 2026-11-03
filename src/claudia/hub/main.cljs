(ns claudia.hub.main
  "Claudia's hub, on the gate behind Caddy: serves the page, knows the servers and who may use which
  (its store, claudia.hub.config), runs its own operations (claudia.hub.ops), and passes /s/<server>/api/… and /s/<server>/ws on to that server's front, saying who
  is calling. Caddy logs people in and sets X-Auth-Request-Email; the hub believes it and nothing
  else from the browser."
  (:require ["fs" :as fs]
            [claudia.node.http :as io]
            [claudia.node.env :refer [env]]
            ["http" :as http]
            ["path" :as path]
            [clojure.string :as str]
            [claudia.hub.config :as config]
            [claudia.hub.github :as github]
            [claudia.hub.ops :as ops]
            [claudia.hub.pairing :as pairing]
            [claudia.hub.pass :as pass]
            [claudia.hub.push :as push]
            [claudia.hub.narrate :as narrate]
            [claudia.hub.web :as web]))

;; The hub's version and commit (the image writes version.json beside hub.js; elsewhere none).
(def ^:private version-info
  (try (js->clj (js/JSON.parse (fs/readFileSync (path/join js/__dirname "version.json") "utf8")) :keywordize-keys true)
       (catch :default _ {:version nil :commit nil})))

(defn- header [^js req k]
  (aget (.-headers req) k))

(defn- caller
  "The signed-in email (lower case) from Caddy's X-Auth-Request-Email, or nil."
  [req]
  (let [v (some-> (header req "x-auth-request-email") str/trim str/lower-case)]
    (when (and v (re-matches #"^[^\s@,]+@[^\s@,]+$" v)) v)))

(defn- cross-site?
  "A request another site's page made, riding the login cookie: refused. Browsers say where a
  request comes from in Sec-Fetch-Site (also on WebSocket handshakes); Origin, when sent, must be
  this host. Requests without either (curl, a navigation typed in) pass."
  [req]
  (let [site (header req "sec-fetch-site")
        origin (header req "origin")
        host (some-> (or (header req "x-forwarded-host") (header req "host"))
                     (str/split #",") first str/trim str/lower-case)]
    (boolean
     (or (and site (not (#{"same-origin" "none"} site)))
         (and origin
              (not= host (try (str/lower-case (.-host (js/URL. origin)))
                              (catch :default _ nil))))))))

(defn- split-url
  "\"/a/b?x=1\" -> [\"/a/b\" \"x=1\"] (query nil when there is none)."
  [url]
  (let [i (str/index-of url "?")]
    (if i [(subs url 0 i) (subs url (inc i))] [url nil])))

(defn- decode [s]
  (try (js/decodeURIComponent s) (catch :default _ nil)))

(defn- take-view
  "The `view` query parameter (PROTOCOL.md: the profile whose chats the page looks at) taken out of
  the query. -> {:view v-or-nil :query \"?rest\"-or-\"\" :error text-or-nil}. The other parameters
  pass on byte for byte."
  [query]
  (let [parts (if (str/blank? query) [] (str/split query #"&"))
        view-part? #(= "view" (decode (first (str/split % #"=" 2))))
        views (map #(decode (or (second (str/split % #"=" 2)) "")) (filter view-part? parts))
        rest-parts (remove view-part? parts)
        view (first views)]
    {:view view
     :query (if (seq rest-parts) (str "?" (str/join "&" rest-parts)) "")
     :error (cond
              (> (count views) 1) "view: give it once"
              (and (seq views) (not (and view (re-matches #"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$" view))))
              "view: a profile name")}))

(defn- safe-rest?
  "The path under /s/<server> may not climb out of /api/ on the front (`..`, `.`, encoded
  slashes)."
  [path]
  (every? (fn [seg]
            (let [d (decode seg)]
              (and d (not (#{"." ".."} d)) (not (re-find #"[/\\\u0000]" d)))))
          (rest (str/split path #"/"))))

(defn- to-server
  "Checks a /s/<server>/… call: a WebSocket handshake (`ws?`) for /s/<server>/ws, a plain request
  for /s/<server>/api/…. -> {:status :error} to refuse, or {:server :target :email :view}."
  [req pathname query ws?]
  (let [[_ name rest-path] (re-matches #"^/s/([a-z][a-z0-9-]{0,30})(/.*)?$" pathname)
        email (caller req)
        person (some-> email config/person)
        server (when (and person name) (config/server-of person name))
        {:keys [view error] q :query} (take-view query)]
    (cond
      (not name) {:status 404 :error "not found"}
      (not email) {:status 401 :error "not signed in"}
      (cross-site? req) {:status 403 :error "cross-site request"}
      (not person) {:status 403 :error "you have no access to Claudia here"}
      (not server) {:status 403 :error (str "no access to " name)}
      (and ws? (not= rest-path "/ws")) {:status 404 :error "not found"}
      (and (not ws?) (= rest-path "/ws")) {:status 426 :error "a WebSocket upgrade is needed"}
      (and (not ws?) (not (some-> rest-path (str/starts-with? "/api/")))) {:status 404 :error "not found"}
      (not (safe-rest? rest-path)) {:status 400 :error "bad path"}
      ;; The hub's own poke to the front (README, Hub operations: following main), never a page's call.
      (= rest-path "/api/update-check") {:status 404 :error "not found"}
      error {:status 400 :error error}
      ;; A secret is only ever read for the caller's own profile (PROTOCOL.md, Secrets).
      (and view (str/starts-with? rest-path "/api/secret/")) {:status 403 :error "a view reads no secrets"}
      :else {:server server :target (str rest-path q) :email email :view view})))

(defn- admin-group?
  "The login's groups (X-Auth-Request-Groups, set by the gate's login like the email) hold
  HUB_ADMIN_GROUP: an admin, also when not in the store yet (the first admin of a fresh hub)."
  [req]
  (let [group (env "HUB_ADMIN_GROUP" nil)]
    (boolean (and group
                  (some #{group} (map str/trim (str/split (or (header req "x-auth-request-groups") "") #",")))))))

(defn- hub-api [^js req ^js res pathname]
  (let [[_ state-of] (re-matches #"^/hub/servers/([a-z][a-z0-9-]{0,30})/state$" pathname)
        email (caller req)
        group-admin (admin-group? req)
        person (or (some-> email config/person)
                   (when (and email group-admin) {:email email :name email :admin true :servers []}))
        admin? (boolean (or (:admin person) group-admin))
        own? (or (= pathname "/hub/me") state-of)]
    (cond
      (ops/open-call? pathname) (ops/open-call req res pathname)
      (and own? (not= "GET" (.-method req))) (do (.setHeader res "allow" "GET") (io/send-json res 405 {:error "GET only"}))
      (not email) (io/send-json res 401 {:error "not signed in"})
      (cross-site? req) (io/send-json res 403 {:error "cross-site request"})
      (not person) (io/send-json res 403 {:error "you have no access to Claudia here"})
      (not own?) (ops/call req res pathname email admin?)

      (= pathname "/hub/me")
      (io/send-json res 200 {:email (:email person)
                          :name (:name person)
                          :admin admin?
                          :push (push/on?)
                          :version (:version version-info)
                          :commit (:commit version-info)
                          :servers (mapv #(select-keys % [:name :label]) (config/servers-of person))})

      :else
      (if-let [server (config/server-of person state-of)]
        (pass/up? server email #(io/send-json res 200 {:up %}))
        (io/send-json res 403 {:error (str "no access to " state-of)})))))

(defn- on-request [web-root ^js req ^js res]
  (let [[pathname query] (split-url (.-url req))]
    (cond
      (str/starts-with? pathname "/s/")
      (let [{:keys [status error server target email view]} (to-server req pathname query false)]
        (if status
          (io/send-json res status {:error error})
          (pass/http! req res server target email view)))

      (str/starts-with? pathname "/hub/")
      (hub-api req res pathname)

      :else
      (web/serve web-root req res pathname))))

(defn- on-upgrade [^js req ^js socket head]
  (let [[pathname query] (split-url (.-url req))
        {:keys [status error server target email view]}
        (if (str/starts-with? pathname "/s/")
          (to-server req pathname query true)
          {:status 404 :error "not found"})]
    (.on socket "error" (fn [_] nil))
    (if status
      (pass/refuse-upgrade socket status (js/JSON.stringify #js {:error error})
                           "application/json; charset=utf-8")
      (pass/ws! req socket head server target email view))))

(defn main [& _args]
  (let [host (env "HUB_HOST" "127.0.0.1")
        port (js/parseInt (env "HUB_PORT" "4590"))
        web-root (env "HUB_WEB" "out/web")
        config-file (env "HUB_CONFIG" nil)
        data-dir (env "HUB_DATA" (path/join (env "XDG_DATA_HOME" (path/join (env "HOME" "/tmp") ".local/share")) "claudia-hub"))]
    (when-not (fs/existsSync (path/join web-root "index.html"))
      (js/console.warn (str "hub: " web-root "/index.html is missing: the page is not built")))
    (try (config/start! data-dir config-file)
         (catch :default e
           (js/console.error (str "hub: store " data-dir " / hub.json " config-file ": " (.-message e)))
           (js/process.exit 1)))
    (pairing/start! data-dir)
    (narrate/start! data-dir)
    (github/start!)
    (push/start!)
    (let [^js server (http/createServer (partial on-request web-root))
          beat (pass/heartbeat!)
          stopping (atom false)
          stop (fn [sig]
                 (when-not @stopping
                   (reset! stopping true)
                   (js/console.log (str "hub: " sig ", stopping"))
                   (push/stop!)
                   (config/stop!)
                   (js/clearInterval beat)
                   (pass/close-all!)
                   (.close server #(js/process.exit 0))
                   (.closeIdleConnections server)
                   ;; Requests still streaming get a few seconds, then everything is cut.
                   (.unref (js/setTimeout #(do (.closeAllConnections server) (js/process.exit 0)) 3000))))]
      ;; Caddy keeps its connections to the hub open for 2 minutes between requests; the hub must
      ;; not close one first, or a request Caddy sends on it just then fails with 502.
      (set! (.-keepAliveTimeout server) 130000)
      ;; Uploads and long answers stream through; Caddy, in front, deals with slow clients.
      (set! (.-requestTimeout server) 0)
      (.on server "upgrade" on-upgrade)
      (.on server "error" (fn [e] (js/console.error (str "hub: " (.-message e))) (js/process.exit 1)))
      ;; As PID 1 in a container the hub gets no default action for these signals: it must exit
      ;; itself, or `docker stop` waits its full timeout and kills it.
      (doseq [sig ["SIGTERM" "SIGINT"]]
        (js/process.on sig #(stop sig)))
      (.listen server port host
               #(js/console.log (str "Claudia hub on http://" host ":" port ", page from " web-root))))))
