(ns claudia.slave.host
  "The session host: owns every chat's Agent SDK query and outlives the runner. It knows nothing about
  Claudia: it starts queries as the runner describes them, streams their messages to the runner, and
  turns every callback (MCP tools, hooks, permission asks) into an rpc to the runner, which waits
  while the runner is away. Restarting it stops every chat, so it stays small and plain JS interop:
  the runner's options go to the SDK as they came."
  (:require ["node:net" :as net]
            ["node:fs" :as fs]
            ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["zod" :as z]
            [claudia.slave.lines :as lines]))

(def socket-path
  (or (.. js/process -env -HOST_SOCKET)
      (str (or (.. js/process -env -XDG_RUNTIME_DIR) "/tmp") "/claudia-host.sock")))

;; id -> {:q query :push fn :end fn :busy atom :closed atom}
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
  "The runner's tools ({server: {tool: {description, inputSchema}}}) as in-process MCP servers whose
  every call is an rpc."
  [id ^js defs]
  (let [out #js {}]
    (doseq [server (js-keys defs)]
      (let [tools (aget defs server)]
        (aset out server
              (sdk/createSdkMcpServer
               #js {:name server
                    :version "1.0.0"
                    :alwaysLoad true
                    :tools (into-array
                            (for [t (js-keys tools)
                                  :let [^js d (aget tools t)]]
                              (sdk/tool t (.-description d)
                                        (.-shape (z/fromJSONSchema (.-inputSchema d)))
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
  (when-let [{:keys [closed end ^js q]} (get @sessions id)]
    (reset! closed true)
    (swap! sessions dissoc id)
    (drop-rpcs id)
    (end)
    (.close q)))

(defn- pump
  "Streams the query's messages to the runner until it ends by itself or is closed."
  [id {:keys [^js q busy closed]}]
  (letfn [(finish [error]
            (when-not @closed
              (swap! sessions dissoc id)
              (drop-rpcs id)
              (send! (if error #js {:ev "ended" :id id :error error} #js {:ev "ended" :id id}))))
          (step []
            (-> (.next q)
                (.then (fn [^js r]
                         (if (or (.-done r) @closed)
                           (finish nil)
                           (let [^js m (.-value r)]
                             (case (.-type m)
                               ("stream_event" "assistant") (reset! busy true)
                               "result" (reset! busy false)
                               nil)
                             (send! #js {:ev "msg" :id id :msg m})
                             (step)))))
                (.catch (fn [e] (finish (message e))))))]
    (step)))

(defn- start! [id ^js spec]
  (close! id)
  (let [{:keys [iterable] :as box} (inbox)
        options (js/Object.assign
                 #js {} (.-options spec)
                 #js {:mcpServers (mcp-servers id (or (.-mcp spec) #js {}))
                      :hooks (hooks id (.-hooks spec))
                      :canUseTool (fn [tool-name input ^js opts]
                                    (rpc id "canUseTool"
                                         #js {:name tool-name :input input :toolUseID (.-toolUseID opts)
                                              :title (.-title opts) :description (.-description opts)}
                                         (.-signal opts)))
                      :stderr (fn [s] (js/console.error (str "[" (subs id 0 8) "] " (.trimEnd s))))})
        s (assoc box
                 :q (sdk/query #js {:prompt iterable :options options})
                 :busy (atom false)
                 :closed (atom false))]
    (swap! sessions assoc id s)
    (pump id s)))

(def ^:private control-methods
  #{"stopTask" "setModel" "applyFlagSettings" "getContextUsage" "supportedCommands" "supportedModels" "setMcp"})

(defn- set-mcp
  "Replaces a live session's tools with the runner's new ones: a server under the same name is kept
  as it was, so all come off first, then the new ones go on."
  [id ^js q ^js defs]
  (-> (.setMcpServers q #js {})
      (.then (fn [off] (.then (.setMcpServers q (mcp-servers id defs)) (fn [on] #js {:off off :on on}))))))

(defn- session-list []
  (into-array (for [[id {:keys [busy]}] @sessions] #js {:id id :busy @busy})))

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
      (-> (js/Promise.resolve (if (= method "setMcp")
                                (set-mcp id (:q s) (aget (or (.-args msg) #js []) 0))
                                (.apply (aget (:q s) method) (:q s) (or (.-args msg) #js []))))
          (.then (fn [v] (answer #js {:value (if (undefined? v) nil v)}))
                 (fn [e] (answer #js {:error (message e)})))))))

(defn- handle [^js msg]
  (let [id (.-id msg)
        s (get @sessions id)]
    (case (.-op msg)
      "start" (start! id (.-spec msg))
      "send" (when s
               (reset! (:busy s) true)
               ((:push s) (user-message id msg)))
      "interrupt" (when s (-> (.interrupt ^js (:q s)) (.catch (fn [_]))))
      "close" (close! id)
      "reply" (reply! msg)
      "list" (send! #js {:ev "sessions" :list (session-list)})
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
  (send! #js {:ev "sessions" :list (session-list)})
  (doseq [[_ {:keys [ev]}] (sort-by key @waiting)]
    (send! ev)))

(defn main [& _]
  (try (fs/unlinkSync socket-path) (catch :default _))
  (let [server (net/createServer connected)]
    (.listen server socket-path
             (fn []
               (fs/chmodSync socket-path (js/parseInt "600" 8))
               (println "claudia host on" socket-path)))))
