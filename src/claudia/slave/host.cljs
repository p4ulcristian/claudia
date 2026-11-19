(ns claudia.slave.host
  "The session host: owns every chat's Agent SDK query and outlives the runner. It knows nothing about
  Claudia: it starts queries as the runner describes them, streams their messages to the runner, and
  turns every callback (MCP tools, hooks, permission asks) into an rpc to the runner, which waits
  while the runner is away. Restarting it stops every chat, so it stays small and plain JS interop:
  the runner's options go to the SDK as they came. Each session loads the newest installed Agent SDK
  when it starts (claudia.slave.sdk-load, README decision 80), so an SDK update needs no restart."
  (:require ["node:net" :as net]
            ["node:fs" :as fs]
            [claudia.slave.sdk-load :as sdk-load]
            [claudia.slave.lines :as lines]))

(def socket-path
  (or (.. js/process -env -HOST_SOCKET)
      (str (or (.. js/process -env -XDG_RUNTIME_DIR) "/tmp") "/claudia-host.sock")))

;; id -> {:spec :busy atom :closed atom :ready {:promise (the query runs: true; it never will: false)}
;;        :run atom {:q query :push fn :end fn :sdk version :module (its sdk-load module) :started (a message came)
;;                   :sent [messages sent before that: a fallback query gets them again]
;;                   :failed [version why] (it fell back from that version)}}
(defonce sessions (atom {}))
;; rpc number -> {:id chat :ev the rpc event (sent again to a new runner) :resolve :reject}
(defonce waiting (atom {}))
(defonce next-rpc (atom 0))
;; The runner's connection; the newest one wins.
(defonce runner (atom nil))

(defn- send! [obj] (lines/write-line @runner obj))

(defn- message [e] (str (or (some-> e .-message) e)))

(defn- rpc [id kind args ^js signal]
  (js/Promise.
   (fn [done fail]
     (let [n (swap! next-rpc inc)
           ev #js {:ev "rpc" :rpc n :id id :kind kind :args args}]
       (swap! waiting assoc n {:id id :ev ev :resolve done :reject fail})
       (when signal
         (.addEventListener signal "abort"
                            (fn []
                              (when (contains? @waiting n)
                                (swap! waiting dissoc n)
                                ;; The runner takes the ask's card away.
                                (send! #js {:ev "cancel" :rpc n :id id})
                                (fail (js/Error. "aborted"))))
                            #js {:once true}))
       (send! ev)))))

(defn- drop-rpcs [id]
  (doseq [[n {wid :id fail :reject}] @waiting
          :when (= wid id)]
    (swap! waiting dissoc n)
    (fail (js/Error. "session closed"))))

(defn- inbox
  "The query's streaming input: an async iterable fed by push, finished by end."
  []
  (let [buf #js []
        wake (atom nil)
        done (atom false)
        wake! #(when-let [w @wake] (reset! wake nil) (w))
        pull (fn pull []
               (cond (pos? (.-length buf)) (js/Promise.resolve #js {:value (.shift buf) :done false})
                     @done (js/Promise.resolve #js {:value js/undefined :done true})
                     :else (js/Promise. (fn [res] (reset! wake #(res (pull)))))))
        iterable #js {}]
    (aset iterable js/Symbol.asyncIterator (fn [] #js {:next pull}))
    {:iterable iterable
     :push (fn [m] (.push buf m) (wake!))
     :end (fn [] (reset! done true) (wake!))}))

(defn- mcp-servers
  "The runner's tools ({server: {tool: {description, inputSchema}}}) as in-process MCP servers of
  the session's SDK (sdk, with the zod beside it) whose every call is an rpc."
  [id {:keys [^js sdk ^js z]} ^js defs]
  (let [out #js {}]
    (doseq [server (js-keys defs)]
      (let [tools (aget defs server)]
        (aset out server
              (.createSdkMcpServer sdk
                                   #js {:name server
                                        :version "1.0.0"
                                        :alwaysLoad true
                                        :tools (into-array
                                                (for [t (js-keys tools)
                                                      :let [^js d (aget tools t)]]
                                                  (.tool sdk t (.-description d)
                                                         (.-shape (.fromJSONSchema z (.-inputSchema d)))
                                                         (fn [args]
                                                           (rpc id "mcp" #js {:server server :tool t :args args} nil)))))}))))
    out))

(defn- hooks [id ^js matchers-by-event]
  (let [out #js {}]
    (doseq [event (js-keys (or matchers-by-event #js {}))]
      (aset out event
            (.map (aget matchers-by-event event)
                  (fn [m index]
                    (js/Object.assign
                     #js {} m
                     #js {:hooks #js [(fn [input]
                                        (rpc id "hook" #js {:event event :index index :input input} nil))]})))))
    out))

(defn- close! [id]
  (when-let [{:keys [closed run]} (get @sessions id)]
    (reset! closed true)
    (swap! sessions dissoc id)
    (drop-rpcs id)
    (let [{:keys [end ^js q]} @run]
      (when end (end))
      (when q (.close q)))))

(defn- report!
  "Tells the runner which SDK version the session runs on, and why not the newest when it fell back."
  [id {:keys [version error]}]
  (send! (cond-> #js {:ev "sdk" :id id :sdk version}
           error (doto (aset "error" error)))))

(declare run-query!)

(defn- fall-back!
  "The session's query failed (why) before its first message: it starts again on the fallback SDK
  with the messages sent so far, and the runner hears why. False when there is none to fall back to."
  [id s why]
  (let [failed (:sdk @(:run s))
        m (when-not (:failed @(:run s)) (sdk-load/fallback failed))]
    (when m
      (js/console.error (str "[" (subs id 0 8) "] Agent SDK " failed " failed (" why "); falling back to " (:version m)))
      (swap! (:run s) assoc :failed [failed why])
      (report! id (assoc m :error (str "Agent SDK " failed " did not start: " why)))
      (run-query! id s m)
      true)))

(defn- first-message!
  "The query's first message: what it fell back from is marked broken (new sessions skip it) and the
  messages kept for a fallback go."
  [s]
  (when-not (:started @(:run s))
    (when-let [[v why] (:failed @(:run s))] (sdk-load/failed! v why))
    (swap! (:run s) assoc :started true :sent nil)))

(defn- ended!
  "The session's query ended (error: why, or nil) and was not closed: the runner hears it."
  [id {:keys [closed]} error]
  (when-not @closed
    (swap! sessions dissoc id)
    (drop-rpcs id)
    (send! (if error #js {:ev "ended" :id id :error error} #js {:ev "ended" :id id}))))

(defn- on-message!
  "One message of the query: the turn's busy flag follows it, and it goes to the runner."
  [id {:keys [busy] :as s} ^js m]
  (first-message! s)
  (case (.-type m)
    ("stream_event" "assistant") (reset! busy true)
    "result" (reset! busy false)
    nil)
  (send! #js {:ev "msg" :id id :msg m}))

(defn- pump
  "Streams query q's messages to the runner until it ends by itself or is closed; one that fails
  before its first message falls back to another SDK. A query a fallback replaced says nothing."
  [id {:keys [closed run] :as s} ^js q]
  (letfn [(current? [] (and (not @closed) (= q (:q @run))))
          (failed [e]
            (when (and (current?) (or (:started @run) (not (fall-back! id s (message e)))))
              (ended! id s (message e))))
          (step []
            (-> (.next q)
                (.then (fn [^js r]
                         (cond (not (current?)) nil
                               (.-done r) (ended! id s nil)
                               :else (do (on-message! id s (.-value r)) (step)))))
                (.catch failed)))]
    (step)))

(defn- options
  "The runner's options for the session as the SDK m takes them: its tools, hooks and asks as rpcs."
  [id ^js spec m]
  (js/Object.assign
   #js {} (.-options spec)
   #js {:mcpServers (mcp-servers id m (or (.-mcp spec) #js {}))
        :hooks (hooks id (.-hooks spec))
        :canUseTool (fn [tool-name input ^js opts]
                      (rpc id "canUseTool"
                           #js {:name tool-name :input input :toolUseID (.-toolUseID opts)
                                :title (.-title opts) :description (.-description opts)}
                           (.-signal opts)))
        :stderr (fn [s] (js/console.error (str "[" (subs id 0 8) "] " (.trimEnd s))))}))

(defn- run-query!
  "Starts the session's query on SDK m, its inbox holding every message sent before the first
  answer, and streams it. A query the SDK refuses at once falls back like one that fails later."
  [id {:keys [spec run ready] :as s} {:keys [^js sdk version] :as m}]
  (let [{:keys [iterable push end]} (inbox)]
    ;; A query that failed before this fallback goes.
    (when-let [^js old (:q @run)] (try (.close old) (catch :default _ nil)))
    (doseq [msg (:sent @run)] (push msg))
    (swap! run assoc :sdk version :module m :push push :end end :q nil)
    (try
      (let [q (.query sdk #js {:prompt iterable :options (options id spec m)})]
        (swap! run assoc :q q)
        ((:resolve ready) true)
        (pump id s q))
      (catch :default e
        (when-not (fall-back! id s (message e)) (ended! id s (message e)))))))

(defn- deferred
  "A promise and the function that resolves it."
  []
  (let [d (atom nil)
        p (js/Promise. (fn [res] (reset! d res)))]
    {:promise p :resolve @d}))

(defn- start!
  "Starts the chat's session on the SDK new sessions take now (sdk-load/load!). Messages sent while
  it loads wait in :sent."
  [id ^js spec]
  (close! id)
  (let [s {:spec spec :busy (atom false) :closed (atom false) :ready (deferred)
           :run (atom {:sent [] :started false})}]
    (swap! sessions assoc id s)
    (-> (sdk-load/load!)
        (.then (fn [m]
                 (when-not @(:closed s)
                   (report! id m)
                   (run-query! id s m))))
        (.catch (fn [e] (ended! id s (str "The Agent SDK did not load: " (message e)))))
        (.finally #((:resolve (:ready s)) false)))))

(defn- push!
  "A user message into the session: kept for a fallback until the query's first message, and into
  the running query's inbox (none yet while its SDK loads)."
  [{:keys [run busy]} m]
  (reset! busy true)
  (let [{:keys [push]} (swap! run #(cond-> % (not (:started %)) (update :sent conj m)))]
    (when push (push m))))

(def ^:private control-methods
  #{"stopTask" "setModel" "applyFlagSettings" "getContextUsage" "supportedCommands" "supportedModels" "setMcp"})

(defn- set-mcp
  "Replaces a live session's tools with the runner's new ones: a server under the same name is kept
  as it was, so all come off first, then the new ones go on."
  [id {:keys [^js q module]} ^js defs]
  (-> (.setMcpServers q #js {})
      (.then (fn [off] (.then (.setMcpServers q (mcp-servers id module defs)) (fn [on] #js {:off off :on on}))))))

(defn- sessions-event
  "The sessions the host holds (each with the SDK version it runs on) and the version a session
  started now would run on (sdk)."
  []
  #js {:ev "sessions"
       :sdk (sdk-load/pick)
       :list (into-array (for [[id {:keys [busy run]}] @sessions]
                           #js {:id id :busy @busy :sdk (:sdk @run)}))})

(defn- user-message
  "A send op as the SDK's user message for session id: images (photos, video frames) go before the text."
  [id ^js msg]
  (cond-> #js {:type "user"
               :message #js {:role "user"
                             :content (if (seq (.-images msg))
                                        (.concat (.map (.-images msg)
                                                       (fn [^js i]
                                                         #js {:type "image"
                                                              :source #js {:type "base64" :media_type (.-media_type i) :data (.-data i)}}))
                                                 #js [#js {:type "text" :text (.-text msg)}])
                                        (.-text msg))}
               :parent_tool_use_id nil
               :session_id id}
    (.-uuid msg) (doto (aset "uuid" (.-uuid msg)))))

(defn- reply! [^js msg]
  (when-let [{done :resolve fail :reject} (get @waiting (.-rpc msg))]
    (swap! waiting dissoc (.-rpc msg))
    (if (some? (.-error msg))
      (fail (js/Error. (.-error msg)))
      (done (.-value msg)))))

(defn- control!
  "A call on the chat's query object (stop a task, read the context use, change the model):
  answered with ev \"control\" and the same req."
  [id s ^js msg]
  (let [req (.-req msg)
        method (.-method msg)
        answer #(send! (js/Object.assign #js {:ev "control" :req req} %))]
    (if-not (and s (contains? control-methods method))
      (answer #js {:error (if s (str "no control method " method) "no session")})
      ;; A session whose SDK still loads answers once its query runs.
      (-> (:promise (:ready s))
          (.then (fn [runs]
                   (let [{:keys [^js q] :as r} @(:run s)]
                     (cond (not (and runs q)) (throw (js/Error. "no session"))
                           (= method "setMcp") (set-mcp id r (aget (or (.-args msg) #js []) 0))
                           :else (.apply (aget q method) q (or (.-args msg) #js []))))))
          (.then (fn [v] (answer #js {:value (if (undefined? v) nil v)}))
                 (fn [e] (answer #js {:error (message e)})))))))

(defn- handle [^js msg]
  (let [id (.-id msg)
        s (get @sessions id)]
    (case (.-op msg)
      "start" (start! id (.-spec msg))
      "send" (when s (push! s (user-message id msg)))
      "interrupt" (when-let [^js q (some-> s :run deref :q)] (-> (.interrupt q) (.catch (fn [_]))))
      "close" (close! id)
      "reply" (reply! msg)
      "list" (send! (sessions-event))
      "control" (control! id s msg)
      (js/console.error "host: unknown op" (.-op msg)))))

(defn- connected
  "A runner (re)connected: it takes over, gets the live sessions, then every rpc still waiting."
  [^js sock]
  (when-let [old @runner] (.end ^js old))
  (reset! runner sock)
  (.on sock "error" (fn [_]))
  (.on sock "close" (fn [] (when (= @runner sock) (reset! runner nil))))
  (lines/on-lines sock (fn [line]
                         (when (= @runner sock)
                           (handle (js/JSON.parse line)))))
  (send! (sessions-event))
  (doseq [[_ {:keys [ev]}] (sort-by key @waiting)]
    (send! ev)))

(defn main [& _]
  (try (fs/unlinkSync socket-path) (catch :default _))
  (let [server (net/createServer connected)]
    ;; Listens once the SDK loaded at start is in (or failed): the first fallback of every session.
    (.then (sdk-load/load-start!)
           (fn []
             (.listen server socket-path
                      (fn []
                        (fs/chmodSync socket-path (js/parseInt "600" 8))
                        (println "claudia host on" socket-path)))))))
