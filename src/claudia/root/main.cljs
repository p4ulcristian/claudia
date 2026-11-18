(ns claudia.root.main
  "claudia-root (README decision 52): runs as root and listens on one socket per agent user,
  <ROOT_SOCKET_DIR>/<user>/root.sock, in a folder only that user may enter, so the socket proves who
  calls. A command runs when it is on the no-tap list (ROOT_NO_TAP), else when that user's runner
  says the chat's root switch is on or the person tapped Allow. Every command is logged."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:net" :as net]
            ["node:os" :as os]
            ["node:path" :as path]
            [claudia.node.env :as env]
            [claudia.root.frames :as frames]
            [claudia.root.rules :as rules]
            [claudia.shared.protocol :as p]))

(def ^:private socket-dir (env/env "ROOT_SOCKET_DIR" "/run/claudia-root"))
(def ^:private no-tap-file (env/env "ROOT_NO_TAP" "/etc/claudia/root-no-tap"))

;; Longer than the runner's 10 minutes for the person's answer.
(def ^:private ask-ms (* 11 60 1000))

(defn- no-tap
  "The no-tap list, read on every command (a changed file counts at once); none when it is missing."
  []
  (rules/no-tap-list (try (fs/readFileSync no-tap-file "utf8") (catch :default _ ""))))

(defn- send! [^js sock frame]
  (when-not (.-destroyed sock) (.write sock (frames/line frame))))

(defn- log! [entry] (js/console.log (rules/log-line entry)))

(defn- refuse! [^js sock entry why]
  (log! (assoc entry :decision (str "refused: " why)))
  (send! sock {:refused why})
  (.end sock))

;; ---- running the command ----

(defn- folder-or-root
  "The command's folder: cwd when it is a folder, else /."
  [cwd]
  (if (and (string? cwd) (try (.isDirectory (fs/statSync cwd)) (catch :default _ false))) cwd "/"))

(defn- pipe-out! [^js sock ^js stream k]
  (.on stream "data" (fn [buf] (doseq [f (frames/data-frames k buf)] (send! sock f)))))

(defn- signal-number [sig] (when sig (aget (.. os -constants -signals) sig)))

(defn- on-input!
  "The shim's stdin frames into the command."
  [^js child frame]
  (let [stdin (.-stdin child)]
    (if (:eof frame)
      (.end stdin)
      (some->> (frames/frame-bytes frame :in) (.write stdin)))))

(defn- run-command!
  "Runs argv as root, streams its output, ends with its exit code; the shim leaving kills it."
  [^js sock {:keys [argv cwd env]} user entry on-frame]
  (let [child (cp/spawn (first argv) (clj->js (rest argv))
                        #js {:cwd (folder-or-root cwd) :env (clj->js (rules/child-env user env))
                             :stdio #js ["pipe" "pipe" "pipe"]})
        failed (atom false)]
    (.on (.-stdin child) "error" (fn [_]))
    (pipe-out! sock (.-stdout child) :out)
    (pipe-out! sock (.-stderr child) :err)
    (reset! on-frame #(on-input! child %))
    (.on sock "close" #(when (and (nil? (.-exitCode child)) (nil? (.-signalCode child))) (.kill child "SIGTERM")))
    (.on child "error" (fn [e]
                         (reset! failed true)
                         (send! sock {:err (.toString (js/Buffer.from (str "sudo: " (first argv) ": " (if (= "ENOENT" (.-code e)) "command not found" (.-message e)) "\n")) "base64")})))
    (.on child "close" (fn [code sig]
                         (let [n (if @failed 127 (rules/exit-code code (signal-number sig)))]
                           (log! (assoc entry :exit n))
                           (send! sock {:exit n})
                           (.end sock))))
    (send! sock {:running true})))

;; ---- asking the runner ----

(defn- ask-runner
  "Promise of the runner's verdict (rules/verdict) for a request of user; none (a refusal) when the
  runner is not there or answers anything but JSON."
  [user {:keys [argv cwd chatKey]}]
  (js/Promise.
   (fn [done]
     (let [socket (rules/runner-socket (env/env "ROOT_RUNNER_SOCKET") (rules/profile-of user))
           body (p/write-json {:chatKey chatKey :argv argv :cwd cwd :user user})
           req (http/request #js {:socketPath socket :method "POST" :path "/api/root/ask" :timeout ask-ms
                                  :headers #js {"content-type" "application/json"}}
                             (fn [^js res]
                               (let [chunks #js []]
                                 (.on res "data" #(.push chunks %))
                                 (.on res "end" #(done (rules/verdict (when (= 200 (.-statusCode res))
                                                                        (frames/read-frame (.toString (js/Buffer.concat chunks) "utf8")))))))))]
       (.on req "timeout" #(.destroy req))
       (.on req "error" #(done (rules/verdict {:why "This user's runner did not answer."})))
       (.end req body)))))

(defn- decide!
  "No-tap runs at once; anything else waits for the runner. The shim gone meanwhile: nothing runs."
  [^js sock req user entry on-frame]
  (if (rules/no-tap? (no-tap) (:argv req))
    (run-command! sock req user (assoc entry :decision "no-tap") on-frame)
    (do (send! sock {:asking true})
        (.then (ask-runner user req)
               (fn [{:keys [allow via why]}]
                 (cond (.-destroyed sock) (log! (assoc entry :decision "gone before the answer"))
                       allow (run-command! sock req user (assoc entry :decision via) on-frame)
                       :else (refuse! sock entry why)))))))

;; ---- the sockets ----

(defn- on-request!
  "The connection's first frame: the request."
  [^js sock user req on-frame]
  (let [entry {:user user :chat (rules/key-tag (:chatKey req)) :argv (:argv req)}]
    (if-let [problem (if req (rules/request-problem req) "The request is not a JSON object.")]
      (refuse! sock entry problem)
      (decide! sock req user entry on-frame))))

(defn- on-connection [user ^js sock]
  (let [on-frame (atom nil)
        got-request (atom false)]
    (.on sock "error" (fn [_]))
    (frames/on-frames sock (fn [frame]
                             (cond (not @got-request) (do (reset! got-request true) (on-request! sock user frame on-frame))
                                   (and frame @on-frame) (@on-frame frame))))))

(defn- listen-in!
  "Listens in the folder of user: the socket root:<the folder's group>, 0660 (the umask keeps it
  closed until then)."
  [dir user gid]
  (let [sock-path (path/join dir "root.sock")
        server (net/createServer #(on-connection user %))]
    (try (fs/unlinkSync sock-path) (catch :default _))
    (.on server "error" #(js/console.error (str "root: " sock-path ": " (.-message %))))
    (.listen server sock-path
             (fn []
               ;; Owned by this process's user (root on a server), the folder's group may connect.
               (fs/chownSync sock-path (js/process.getuid) gid)
               (fs/chmodSync sock-path (js/parseInt "660" 8))
               (js/console.log (str "claudia-root listening for " user " on " sock-path))))))

(defn- serve-all!
  "A socket in every agent user's folder of the socket folder."
  []
  (doseq [n (try (vec (fs/readdirSync socket-dir)) (catch :default _ []))
          :let [dir (path/join socket-dir n)
                ^js st (try (fs/statSync dir) (catch :default _ nil))]
          :when (and st (.isDirectory st))]
    (if (rules/serve-dir? n (.-mode st))
      (listen-in! dir n (.-gid st))
      (js/console.error (str "root: not serving " dir ": not an agent user's folder, or others may enter it")))))

(defn main [& _]
  (js/process.umask (js/parseInt "177" 8))
  (js/console.log (str "claudia-root: sockets in " socket-dir ", no-tap list " no-tap-file))
  (serve-all!))
