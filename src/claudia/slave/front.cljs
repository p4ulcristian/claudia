(ns claudia.slave.front
  "This server's front: believes the hub's X-Claudia-Email (only the hub can reach it: firewall), picks
  the caller's profile, and passes HTTP and WebSocket on to that profile's runner socket. No page."
  (:require ["node:http" :as http]
            [claudia.node.http :as io]
            ["node:net" :as net]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.node.system :as system]
            [claudia.shared.protocol :as p]
            [claudia.slave.http :as h]
            [claudia.slave.tokens :as tokens]))

(def ^:private env (.-env js/process))

(defn parse-profiles
  "The profiles file's text: `<profile> <email> <runner socket>` per line; # starts a comment."
  [text]
  (vec (for [line (str/split-lines text)
             :let [[profile email socket] (str/split (str/trim (str/replace line #"#.*" "")) #"\s+")]
             :when (and (seq profile) (seq email) (seq socket))]
         {:profile profile :email (str/lower-case email) :socket socket})))

(def ^:private profiles (atom []))

(defn- caller-email [^js headers]
  (some-> (aget headers "x-claudia-email") str/trim str/lower-case))

(defn route-of
  "Whose runner a call goes to among profiles, for the caller's email and the profile it views
  (blank = its own): {:own :target :read-only}, or {:status :error}."
  [profile-list email view]
  (let [own (some #(when (= (:email %) email) %) profile-list)
        target (when (seq view) (some #(when (= (:profile %) view) %) profile-list))]
    (cond (not (seq email)) {:status 401 :error "No caller."}
          (not own) {:status 403 :error "No profile on this server for this person."}
          (and (seq view) (not target)) {:status 404 :error (str "No profile " view " on this server.")}
          :else {:own own
                 :target (or target own)
                 :read-only (boolean (and target (not= (:profile target) (:profile own))))})))

(defn- route
  "Whose runner the call goes to: {:own :target :read-only}, or {:status :error}."
  [^js headers]
  (route-of @profiles (caller-email headers) (some-> (aget headers "x-claudia-view") str/trim)))

(defn runner-headers
  "The request's headers for the runner: every X-Claudia-* the caller sent replaced by the front's own."
  [^js raw {:keys [target read-only]}]
  (let [pairs (->> (partition 2 raw)
                   (remove #(str/starts-with? (str/lower-case (first %)) "x-claudia-")))]
    (concat pairs
            [["X-Claudia-Profile" (:profile target)]]
            (when read-only [["X-Claudia-Read-Only" "1"]]))))

(defn- me [res {:keys [own target read-only]}]
  (io/send-json res 200 {:server (.-FRONT_NAME env)
                         :profile (:profile own)
                         :profiles (mapv :profile @profiles)
                         :viewing (when read-only (:profile target))
                         :readOnly read-only
                         :version h/version
                         :commit h/commit}))

(defn trigger-line
  "The lines the hub's poke adds to CLAUDIA_UPDATE_TRIGGER: \"stage <tree>\" (body {:stage tree}: a
  git tree to fetch ahead), \"main <commit>\" (body {:main commit}: main was pushed to it, which
  GitHub's API may name a few seconds later), else \"check <time>\" (look at main now); first
  \"releases <url>\" when the body names where the hub keeps the release builder's files (an https
  address, README decision 29)."
  [body iso]
  (let [hex? #(and (string? %) (re-matches #"^[0-9a-f]{40}$" %))
        url (when (string? (:releases body))
              (re-matches #"^https://[A-Za-z0-9.-]+(?::\d+)?/hub/releases/files$" (:releases body)))]
    (str (when url (str "releases " url "\n"))
         (cond (hex? (:stage body)) (str "stage " (:stage body) "\n")
               (hex? (:main body)) (str "main " (:main body) "\n")
               :else (str "check " iso "\n")))))

(defn- update-check
  "The hub's poke (README, Hub operations: following main): adds a line to CLAUDIA_UPDATE_TRIGGER,
  which a path unit watches to start deploy/update.sh. Says only \"look now\" or \"fetch this tree
  ahead\": the update fetches and verifies what it installs by itself."
  [^js req ^js res]
  (if-let [file (not-empty (.-CLAUDIA_UPDATE_TRIGGER env))]
    (-> (io/read-json req 4096)
        (.catch (fn [_] {}))
        (.then (fn [body]
                 (try (fs/appendFileSync file (trigger-line body (.toISOString (js/Date.))))
                      (io/send-json res 202 {:ok true})
                      (catch :default e (io/send-json res 500 {:error (.-message e)}))))))
    (io/send-json res 501 {:error "no update unit here"})))

(defn- token-answer [^js res v]
  (if (:error v)
    (io/send-json res (or (:status v) 400) (dissoc v :status))
    (io/send-json res 200 v)))

(defn- token-current
  "The hub's own calls about the Claude token in use here (README decision 10): GET what it is (no
  token), PUT the one the hub keeps in use. No caller: only the hub reaches this listener."
  [^js req ^js res]
  (case (.-method req)
    "GET" (io/send-json res 200 (tokens/current))
    "PUT" (.then (io/read-json req 65536) #(token-answer res (tokens/write-current! %)))
    (io/send-json res 405 {:error "GET or PUT"})))

(defn- token-saved
  "The hub's own calls about every saved Claude token (README decision 16): GET which are kept here
  (no tokens), PUT the hub's whole set. No caller: only the hub reaches this listener."
  [^js req ^js res]
  (case (.-method req)
    "GET" (io/send-json res 200 {:tokens (tokens/saved)})
    "PUT" (.then (io/read-json req 262144) #(token-answer res (tokens/write-saved! %)))
    (io/send-json res 405 {:error "GET or PUT"})))

(defn newest-heard
  "The runners' kept limits ({token key {:limits :at}} each) as one map, the newest per token."
  [answers]
  (reduce (fn [m [k v]]
            (if (or (not (contains? m k)) (> (or (:at v) 0) (or (:at (get m k)) 0))) (assoc m k v) m))
          {} (mapcat seq answers)))

(defn- runner-limits
  "Promise of one runner's kept limits ({token key {:limits :at}}), {} when it doesn't answer (2 s)."
  [{:keys [profile socket]}]
  (js/Promise.
   (fn [done]
     (let [on-answer (fn [^js up]
                       (let [chunks #js []]
                         (.on up "data" #(.push chunks %))
                         (.on up "end" #(done (or (try (:tokens (p/read-json (.toString (js/Buffer.concat chunks) "utf8")))
                                                       (catch :default _ nil))
                                                  {})))))
           ^js up (http/request #js {:socketPath socket :method "GET" :path "/api/limits" :timeout 2000
                                     :headers #js {"X-Claudia-Profile" profile}}
                                on-answer)]
       (.on up "timeout" #(.destroy up))
       (.on up "error" (fn [_] (done {})))
       (.end up)))))

(defn- token-limits
  "The hub's own call (README decision 10): every profile's kept limits per token (a hash of it,
  never the token), the newest of each, for the hub's popup of every token's limits."
  [^js req ^js res]
  (if (= "GET" (.-method req))
    (.then (js/Promise.all (into-array (map runner-limits (map first (vals (group-by :socket @profiles))))))
           #(io/send-json res 200 {:tokens (newest-heard (map (fn [m] (update-keys m name)) %))}))
    (io/send-json res 405 {:error "GET only"})))

(defn- system-sample
  "The hub's own call (README decision 12): this machine's newest numbers for the Systems popup."
  [^js req ^js res]
  (if (= "GET" (.-method req))
    (-> (system/latest!)
        (.then #(io/send-json res 200 %))
        (.catch #(io/send-json res 500 {:error (.-message %)})))
    (io/send-json res 405 {:error "GET only"})))

(defn- on-request [^js req ^js res]
  (let [{:keys [status error target read-only] :as r} (route (.-headers req))
        url (first (str/split (.-url req) #"\?"))]
    (cond
      ;; From the hub itself (no caller): only the hub reaches this listener.
      (= url "/api/update-check")
      (if (= "POST" (.-method req)) (update-check req res) (io/send-json res 405 {:error "POST only"}))
      (= url "/api/claude-token/current") (token-current req res)
      (= url "/api/claude-token/saved") (token-saved req res)
      (= url "/api/claude-token/limits") (token-limits req res)
      (= url "/api/system") (system-sample req res)
      status (io/send-json res status {:error error})
      (and (= url "/api/me") (= "GET" (.-method req))) (me res r)
      (and read-only (not= "GET" (.-method req))) (io/send-json res 403 {:error "Read only."})
      :else
      (let [headers (reduce (fn [o [k v]] (doto o (aset k v))) #js {} (runner-headers (.-rawHeaders req) r))
            up (http/request #js {:socketPath (:socket target) :method (.-method req) :path (.-url req) :headers headers}
                             (fn [^js up-res]
                               (.writeHead res (.-statusCode up-res) (.-rawHeaders up-res))
                               (.pipe up-res res)))]
        (.on up "error" (fn [_]
                          (if (.-headersSent res)
                            (.destroy res)
                            (io/send-json res 502 {:error "The runner is not answering."}))))
        (.pipe req up)))))

(defn request-head
  "An HTTP/1.1 request's first line and headers ([name value] pairs) as text, the blank line included."
  [method url pairs]
  (str method " " url " HTTP/1.1\r\n"
       (apply str (for [[k v] pairs] (str k ": " v "\r\n")))
       "\r\n"))

(defn- on-upgrade
  "A WebSocket: the upgrade request is written to the runner socket as it came (with the front's
  headers), then bytes flow both ways untouched. Read-only frames are enforced by the runner."
  [^js req ^js sock ^js head]
  (let [{:keys [status error target] :as r} (route (.-headers req))]
    (if status
      (.end sock (str "HTTP/1.1 " status " " error "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"))
      (let [up (net/connect (:socket target))]
        (.on up "connect"
             (fn []
               (.write up (request-head (.-method req) (.-url req) (runner-headers (.-rawHeaders req) r)))
               (when (pos? (.-length head)) (.write up head))
               (.pipe up sock)
               (.pipe sock up)))
        (.on up "error" (fn [_] (.destroy sock)))
        (.on sock "error" (fn [_] (.destroy up)))))))

(defn main [& _]
  (let [file (.-FRONT_PROFILES env)]
    (when-not (seq file) (throw (js/Error. "FRONT_PROFILES is not set.")))
    (reset! profiles (parse-profiles (fs/readFileSync file "utf8")))
    (let [server (doto (http/createServer on-request)
                   ;; Uploads take up to 100 MB: no 5-minute cap on a request.
                   (aset "requestTimeout" 0))
          port (js/parseInt (or (.-FRONT_PORT env) "4540") 10)
          host (or (.-FRONT_HOST env) "127.0.0.1")]
      (.on server "upgrade" on-upgrade)
      ;; FRONT_HOST is the tunnel's address: while the tunnel is down there is nothing to listen on.
      ;; The front stops and its service manager starts it again (systemd Restart, launchd KeepAlive).
      (.on server "error" (fn [e]
                            (js/console.error "claudia front cannot listen on" (str host ":" port) "-" (.-message e))
                            (js/process.exit 1)))
      (.listen server port host
               #(println "claudia front" (.-FRONT_NAME env) "on" (str host ":" port)
                         "for" (count @profiles) "profiles")))))
