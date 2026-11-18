(ns claudia.slave.front
  "This server's front: answers only calls carrying this server's hub key (X-Claudia-Hub-Key, the
  file FRONT_HUB_KEY_FILE; README decision 47), believes their X-Claudia-Email, picks the caller's
  profile, and passes HTTP and WebSocket on to that profile's runner socket. No page."
  (:require ["node:http" :as http]
            [claudia.node.http :as io]
            ["node:net" :as net]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [claudia.node.crypto :as crypto]
            [claudia.node.system :as system]
            [claudia.shared.protocol :as p]
            [claudia.slave.http :as h]
            [claudia.slave.rootfront :as rootfront]
            [claudia.slave.sshfront :as sshfront]
            [claudia.slave.tokens :as tokens]))

(def ^:private env (.-env js/process))

(defn profile-user
  "Whom a profile's host and runner run as, from the words after its socket on its profiles line: the
  first `user=<name>`, else `claudia-<profile>` (as deploy/shared-server/install.sh's profile_user)."
  [profile more]
  (or (some #(second (re-matches #"user=(.+)" %)) more) (str "claudia-" profile)))

(defn parse-profiles
  "The profiles file's text: `<profile> <email> <runner socket> [user=<linux user>]` per line; # starts
  a comment. The user is whom the profile's host and runner run as: the front renews an agent
  user's SSH certificate (claudia.slave.sshfront) and believes no runner about it."
  [text]
  (vec (for [line (str/split-lines text)
             :let [[profile email socket & more] (str/split (str/trim (str/replace line #"#.*" "")) #"\s+")]
             :when (and (seq profile) (seq email) (seq socket))]
         {:profile profile :email (str/lower-case email) :socket socket :user (profile-user profile more)})))

(def ^:private profiles (atom []))

(defonce ^:private hub-key (atom nil))

(defn from-hub?
  "Whether a call carries this server's hub key (X-Claudia-Hub-Key): the only proof it is the hub's."
  [^js headers own-key]
  (boolean (and (seq own-key) (crypto/same-secret? (aget headers "x-claudia-hub-key") own-key))))

(defn read-hub-key
  "The key file's text, trimmed; throws when it is missing or too short to be one."
  [file]
  (when-not (seq file) (throw (js/Error. "FRONT_HUB_KEY_FILE is not set: the front could tell no call from the hub's.")))
  (let [k (str/trim (fs/readFileSync file "utf8"))]
    (when (< (count k) 40) (throw (js/Error. (str file " does not hold a hub key."))))
    k))

(defn- caller-email [^js headers]
  (some-> (aget headers "x-claudia-email") str/trim str/lower-case))

(defn- caller-writes
  "The emails whose chats the caller may write to (the hub's X-Claudia-Writes), as a set."
  [^js headers]
  (into #{} (comp (map str/trim) (map str/lower-case) (filter seq))
        (str/split (or (aget headers "x-claudia-writes") "") #",")))

(defn route-of
  "Whose runner a call goes to among profiles, for the caller's email, the profile it views (blank =
  its own) and the emails whose chats it may write to: {:own :target :viewing :read-only}, or
  {:status :error}. Viewing another profile is read only unless its owner is in writes. A caller
  with no profile here may look (read only): at another profile's chats, or its /api/me (target nil)."
  [profile-list email view writes]
  (let [own (some #(when (= (:email %) email) %) profile-list)
        target (when (seq view) (some #(when (= (:profile %) view) %) profile-list))
        other? (boolean (and target (not= (:profile target) (:profile own))))]
    (cond (not (seq email)) {:status 401 :error "No caller."}
          (and (seq view) (not target)) {:status 404 :error (str "No profile " view " on this server.")}
          (not own) {:own nil :target target :viewing (:profile target) :read-only true}
          :else {:own own
                 :target (or target own)
                 :viewing (when other? (:profile target))
                 :read-only (and other? (not (contains? (set writes) (:email target))))})))

(defn writable-profiles
  "The other profiles here the caller may write to: those whose owner's email is in writes."
  [profile-list own writes]
  (vec (for [{:keys [profile email]} profile-list
             :when (and own (not= profile (:profile own)) (contains? (set writes) email))]
         profile)))

(defn- route
  "Whose runner the call goes to: {:own :target :viewing :read-only}, or {:status :error}."
  [^js headers]
  (route-of @profiles (caller-email headers) (some-> (aget headers "x-claudia-view") str/trim)
            (caller-writes headers)))

(defn runner-headers
  "The request's headers for the runner: every X-Claudia-* the caller sent replaced by the front's own."
  [^js raw {:keys [target read-only]}]
  (let [pairs (->> (partition 2 raw)
                   (remove #(str/starts-with? (str/lower-case (first %)) "x-claudia-")))]
    (concat pairs
            [["X-Claudia-Profile" (:profile target)]]
            (when read-only [["X-Claudia-Read-Only" "1"]]))))

(defn root-call
  "What the front does with a hub call to url under /api/root/ (README decision 52) or /api/ssh/
  (decision 53): :front (the person's root tap, answered here), :refuse (a runner's root and SSH
  certificate routes are the front's own, never passed on), or nil."
  [method url]
  (cond (and (= "POST" method) (#{"/api/root/answer" "/api/root/switch"} url)) :front
        (str/starts-with? url "/api/root/") :refuse
        (str/starts-with? url "/api/ssh/") :refuse))

(defn- me [res headers {:keys [own viewing read-only]}]
  (io/send-json res 200 {:server (.-FRONT_NAME env)
                         :profile (:profile own)
                         :profiles (mapv :profile @profiles)
                         ;; Whose each profile is (the page's profile circles take the hub's pictures by it).
                         :owners (into {} (map (juxt :profile :email)) @profiles)
                         :writable (writable-profiles @profiles own (caller-writes headers))
                         :viewing viewing
                         :readOnly read-only
                         :version h/version
                         :commit h/commit}))

(defn trigger-line
  "The lines the hub's poke adds to CLAUDIA_UPDATE_TRIGGER: \"rollback <commit>\" (body {:rollback
  commit}: that release was broken in people's pages, go back one, README decision 35), \"stage
  <tree>\" (body {:stage tree}: a git tree to fetch ahead), \"main <commit>\" (body {:main commit}:
  main was pushed to it, which GitHub's API may name a few seconds later), else \"check <time>\"
  (look at main now); first
  \"releases <url>\" when the body names where the hub keeps the release builder's files (an https
  address, README decision 29)."
  [body iso]
  (let [hex? #(and (string? %) (re-matches #"^[0-9a-f]{40}$" %))
        url (when (string? (:releases body))
              (re-matches #"^https://[A-Za-z0-9.-]+(?::\d+)?/hub/releases/files$" (:releases body)))]
    (str (when url (str "releases " url "\n"))
         (cond (hex? (:rollback body)) (str "rollback " (:rollback body) "\n")
               (hex? (:stage body)) (str "stage " (:stage body) "\n")
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

(defn- token-picks
  "The hub's own calls about each person's last pick (README decision 57): GET which are kept here,
  PUT the hub's whole set (kept by the profile here whose email each is). No caller: only the hub
  reaches this listener."
  [^js req ^js res]
  (case (.-method req)
    "GET" (io/send-json res 200 {:picks (tokens/picks)})
    "PUT" (.then (io/read-json req 65536) #(token-answer res (tokens/write-picks! % @profiles)))
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

(def ^:private retry-ms "How soon a call a restarting runner refused tries again." 300)
(def ^:private retry-for-ms
  "How long a call keeps trying a runner that refuses it: one restarting for an update is back
  within a second or two, so the call waits instead of failing."
  15000)

(defn retry?
  "Whether a failed try to reach a runner is tried again: its socket refused or is missing for the
  moment (it is restarting), the call has no body that already went out (GET, HEAD, a WebSocket
  handshake), and there is time left."
  [method code now deadline]
  (boolean (and (#{"GET" "HEAD"} method)
                (#{"ECONNREFUSED" "ENOENT" "ECONNRESET"} code)
                (< now deadline))))

(defn- to-runner!
  "One try of passing the call on to the runner r names; a refused one tries again (retry?)."
  [^js req ^js res {:keys [target] :as r} deadline]
  (let [method (.-method req)
        headers (reduce (fn [o [k v]] (doto o (aset k v))) #js {} (runner-headers (.-rawHeaders req) r))
        up (http/request #js {:socketPath (:socket target) :method method :path (.-url req) :headers headers}
                         (fn [^js up-res]
                           (.writeHead res (.-statusCode up-res) (.-rawHeaders up-res))
                           (.pipe up-res res)))]
    (.on up "error" (fn [^js e]
                      (cond
                        (.-headersSent res) (.destroy res)
                        (retry? method (.-code e) (js/Date.now) deadline)
                        (js/setTimeout #(to-runner! req res r deadline) retry-ms)
                        :else (io/send-json res 502 {:error "The runner is not answering."}))))
    (if (#{"GET" "HEAD"} method) (.end up) (.pipe req up))))

(defn- on-request [^js req ^js res]
  (let [{:keys [status error read-only] :as r} (route (.-headers req))
        url (first (str/split (.-url req) #"\?"))]
    (cond
      (not (from-hub? (.-headers req) @hub-key)) (io/send-json res 401 {:error "Not the hub."})
      ;; From the hub itself (no caller).
      (= url "/api/update-check")
      (if (= "POST" (.-method req)) (update-check req res) (io/send-json res 405 {:error "POST only"}))
      (= url "/api/claude-token/picks") (token-picks req res)
      (= url "/api/claude-token/saved") (token-saved req res)
      (= url "/api/claude-token/limits") (token-limits req res)
      (= url "/api/system") (system-sample req res)
      ;; The commit this machine runs (the update pill follows each one after a release).
      (= url "/api/version") (io/send-json res 200 {:commit h/commit :version h/version})
      status (io/send-json res status {:error error})
      (= :refuse (root-call (.-method req) url)) (io/send-json res 403 {:error "Not passed on: the runner's root and SSH routes are the front's own."})
      (and (= url "/api/me") (= "GET" (.-method req))) (me res (.-headers req) r)
      (not (:target r)) (io/send-json res 403 {:error "No profile on this server for this person."})
      (and read-only (not= "GET" (.-method req))) (io/send-json res 403 {:error "Read only."})
      (= :front (root-call (.-method req) url)) (rootfront/handle @profiles r url req res)
      :else (to-runner! req res r (+ (js/Date.now) retry-for-ms)))))

(defn request-head
  "An HTTP/1.1 request's first line and headers ([name value] pairs) as text, the blank line included."
  [method url pairs]
  (str method " " url " HTTP/1.1\r\n"
       (apply str (for [[k v] pairs] (str k ": " v "\r\n")))
       "\r\n"))

(defn- splice!
  "One try of the WebSocket's way to the runner: the upgrade request written to its socket as it came
  (with the front's headers), then bytes both ways untouched; a refused try waits and tries again."
  [^js req ^js sock ^js head {:keys [target] :as r} deadline]
  (let [up (net/connect (:socket target))
        joined (atom false)]
    (.on up "connect"
         (fn []
           (reset! joined true)
           (.write up (request-head (.-method req) (.-url req) (runner-headers (.-rawHeaders req) r)))
           (when (pos? (.-length head)) (.write up head))
           (.pipe up sock)
           (.pipe sock up)))
    (.on up "error" (fn [^js e]
                      (if (and (not @joined) (not (.-destroyed sock)) (retry? "GET" (.-code e) (js/Date.now) deadline))
                        (js/setTimeout #(splice! req sock head r deadline) retry-ms)
                        (.destroy sock))))
    (.on sock "error" (fn [_] (.destroy up)))))

(defn- on-upgrade
  "A WebSocket: passed on to the runner's socket (splice!). Read-only frames are enforced by the runner."
  [^js req ^js sock ^js head]
  (let [{:keys [status error] :as r} (if (from-hub? (.-headers req) @hub-key)
                                       (route (.-headers req))
                                       {:status 401 :error "Not the hub."})]
    (if (or status (not (:target r)))
      (.end sock (str "HTTP/1.1 " (or status 403) " " (or error "No profile here") "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"))
      (splice! req sock head r (+ (js/Date.now) retry-for-ms)))))

(defn main [& _]
  (let [file (.-FRONT_PROFILES env)]
    (when-not (seq file) (throw (js/Error. "FRONT_PROFILES is not set.")))
    (reset! profiles (parse-profiles (fs/readFileSync file "utf8")))
    (reset! hub-key (read-hub-key (.-FRONT_HUB_KEY_FILE env)))
    (rootfront/listen! profiles)
    (sshfront/start! @profiles)
    (let [server (doto (http/createServer on-request)
                   ;; Uploads take up to 100 MB: no 5-minute cap on a request.
                   (aset "requestTimeout" 0))
          port (js/parseInt (or (.-FRONT_PORT env) "4540") 10)
          host (or (.-FRONT_HOST env) "127.0.0.1")]
      (.on server "upgrade" on-upgrade)
      ;; FRONT_HOST may be a network's address: while that network is down there is nothing to listen on.
      ;; The front stops and its service manager starts it again (systemd Restart, launchd KeepAlive).
      (.on server "error" (fn [e]
                            (js/console.error "claudia front cannot listen on" (str host ":" port) "-" (.-message e))
                            (js/process.exit 1)))
      (.listen server port host
               #(println "claudia front" (.-FRONT_NAME env) "on" (str host ":" port)
                         "for" (count @profiles) "profiles")))))
