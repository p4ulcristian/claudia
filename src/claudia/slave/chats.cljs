(ns claudia.slave.chats
  "Every chat of this profile: starting and resuming its session in the host, turning the SDK's
  messages into page events, the draw and workspace tools, questions and secret asks."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [claudia.slave.history :as history]
            [claudia.slave.hostlink :as hostlink]
            [claudia.slave.pictures :as pictures]
            [claudia.slave.render :as render]
            [claudia.slave.scene :as scene]
            [claudia.slave.secret :as secret]
            [claudia.slave.store :as store]
            [claudia.slave.tools :as tools]))

(def ^:private env (.-env js/process))

(def cwd (or (.-CLAUDIA_CWD env) (path/join (os/homedir) "Work")))

;; A chat whose turn ended this long ago lets its Claude Code process go; the next message resumes it.
(def ^:private idle-ms (* 15 60 1000))
(def ^:private secret-ms (* 10 60 1000))

(def chat-colors ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan"
                  "sky" "blue" "indigo" "violet" "purple" "magenta" "pink" "brown"])

;; id -> what lives only while the runner runs:
;;   :live (the host holds a session) :running :stopping :scene
;;   :asks {ask id -> {:ask (as the page gets it) :resolve :input :keys :secret :timer}}
;;   :turn (this turn's Msgs, by arrival) :partial (the assistant text being streamed)
;;   :mid (the API message streaming) :stream-counts :final-counts :idle (timer)
;;   :turn-id (the user message uuid this turn answers: its picture's name in the history)
(defonce state (atom {}))

;; Set by the runner: where events go.
(defonce out (atom {:emit (fn [_ _]) :changed (fn [])}))

(defn- now [] (js/Date.now))
(defn- message [e] (str (or (some-> e .-message) e)))
(defn- st [id] (get @state id))
(defn- swap-chat! [id f & args] (swap! state update id #(apply f (or % {}) args)))
(defn- emit! [id frame] ((:emit @out) id (assoc frame :chat id)))
(defn- changed! [] ((:changed @out)))

(defn- plain-start
  "The start of a reply as one plain line (the chat list's preview)."
  [text]
  (let [t (-> (history/strip-reminders text)
              (str/replace #"```[^\n]*" "")
              (str/replace #"[*_`#>|]" "")
              (str/replace #"\s+" " ")
              str/trim)]
    (if (> (count t) 200) (str (subs t 0 199) "…") t)))

(defn rows
  "The chat list (Row in PROTOCOL.md)."
  []
  (vec (for [r (store/chats)
             :let [s (st (:id r))
                   asks (map :ask (vals (:asks s)))
                   question (some #(get-in % [:questions 0 :question]) asks)]]
         {:id (:id r) :title (:title r) :folder (:folder r) :color (:color r)
          :running (boolean (:running s))
          :waiting (boolean (seq asks))
          :at (:at r)
          :preview (or question (some #(when (= "secret" (:kind %)) (str "Secret: " (:reason %))) asks) (:preview r))
          :unseen (> (:ended r) (:seen r))
          :archived (:archived r)
          ;; For the hub's phone alerts: when the last turn ended, and the open asks.
          :ended (:ended r)
          :asks (vec (for [a asks]
                       {:id (:id a) :kind (:kind a)
                        :text (if (= "secret" (:kind a)) (:reason a) (get-in a [:questions 0 :question]))}))})))

(defn- note-msg!
  "A new or changed Msg of this turn: kept (so a chat opened mid-turn has it) and sent."
  [id msg]
  (swap-chat! id update :turn
              (fn [t]
                (let [t (vec t)]
                  (if-let [i (first (keep-indexed #(when (= (:id %2) (:id msg)) %1) t))]
                    (assoc t i msg)
                    (conj (if (> (count t) 400) (subvec t 1) t) msg)))))
  (emit! id {:type "msg" :msg msg}))

(defn- arm-idle! [id]
  (some-> (:idle (st id)) js/clearTimeout)
  (swap-chat! id assoc :idle
              (when-not (:running (st id))
                (js/setTimeout (fn []
                                 (when (and (:live (st id)) (not (:running (st id))))
                                   (hostlink/send! {:op "close" :id id})
                                   (swap-chat! id assoc :live false)))
                               idle-ms))))

(defn- set-running! [id running]
  (when (not= running (boolean (:running (st id))))
    (swap-chat! id assoc :running running)
    (store/update! id {:at (now)})
    (emit! id {:type "turn" :running running})
    (changed!))
  (arm-idle! id))

;; Asks -------------------------------------------------------------------------------------------

(defn- add-ask! [id entry]
  (swap-chat! id assoc-in [:asks (get-in entry [:ask :id])] entry)
  (emit! id {:type "ask" :ask (:ask entry)})
  (changed!))

(defn- take-ask!
  "Removes an ask and tells the page; returns its entry."
  [id ask-id]
  (when-let [entry (get-in (st id) [:asks ask-id])]
    (some-> (:timer entry) js/clearTimeout)
    (swap-chat! id update :asks dissoc ask-id)
    (emit! id {:type "asked" :ask ask-id})
    (changed!)
    entry))

(defn- refusal [text] {:content [{:type "text" :text text}] :isError true})

(defn- drop-asks!
  "Ends every open ask of a chat; answer? false when nobody is left to answer (the host went away and
  sends its rpcs again)."
  [id answer? why]
  (doseq [ask-id (keys (:asks (st id)))
          :let [entry (take-ask! id ask-id)]]
    (when answer?
      ((:resolve entry) (if (= "question" (get-in entry [:ask :kind]))
                          {:behavior "deny" :message why :interrupt true}
                          (refusal (str why " Nothing ran.")))))))

(defn answer! [id ask-id answers]
  (when-let [entry (get-in (st id) [:asks ask-id])]
    (when (= "question" (get-in entry [:ask :kind]))
      (take-ask! id ask-id)
      ((:resolve entry) {:behavior "allow" :updatedInput (assoc (:input entry) :answers answers)})
      (let [text (history/answer-text (get-in entry [:ask :questions]) answers)]
        (when (seq text)
          (note-msg! id {:id (str "answer-" ask-id) :role "user" :text text :at (now)}))))))

(defn deny! [id ask-id who]
  (when-let [entry (take-ask! id ask-id)]
    (if (= "secret" (get-in entry [:ask :kind]))
      (do (js/console.log (str "secret " (get-in entry [:ask :ref]) " (" (get-in entry [:ask :reason]) ") denied by " who))
          ((:resolve entry) (refusal "The user said no to this secret. Nothing ran.")))
      ((:resolve entry) {:behavior "deny" :message "The user said no."}))))

(defn secret!
  "The sealed value for a secret ask: opened, used by its one command, dropped."
  [id ask-id sealed who]
  (when-let [entry (get-in (st id) [:asks ask-id])]
    (when (= "secret" (get-in entry [:ask :kind]))
      (let [value (try (secret/open sealed (:keys entry)) (catch :default _ nil))]
        (if-not (seq value)
          (emit! id {:type "error" :text "That secret did not open (sealed to another request?). The ask is still open."})
          (let [{:keys [command env_name]} (:secret entry)
                {:keys [ref reason]} (:ask entry)]
            (take-ask! id ask-id)
            (js/console.log (str "secret " ref " (" reason ") allowed by " who))
            (-> (secret/run command env_name value (or (:folder (store/chat id)) cwd))
                (.then (fn [{:keys [text error]}]
                         ((:resolve entry) (cond-> {:content [{:type "text" :text text}]}
                                             error (assoc :isError true))))))))))))

(defn- secret-ask [id {:keys [ref env_name reason command]}]
  (cond
    (not (secret/env-name? env_name)) (refusal (str "env_name must be a shell variable name, not \"" env_name "\"."))
    (not (seq command)) (refusal "No command given.")
    :else
    (js/Promise.
     (fn [resolve]
       (let [kp (secret/keypair)
             ask-id (str (random-uuid))
             ask {:id ask-id :kind "secret" :ref ref :reason reason
                  :account (or (.-CLAUDIA_OP_ACCOUNT env) "")
                  :vault (or (.-CLAUDIA_VAULT_SERVER env) "")
                  :pubkey (:pubkey kp) :expires (+ (now) secret-ms)}]
         (add-ask! id {:ask ask :resolve resolve :keys kp
                       :secret {:command command :env_name env_name}
                       :timer (js/setTimeout (fn []
                                               (when (take-ask! id ask-id)
                                                 (js/console.log (str "secret " ref " (" reason ") expired"))
                                                 (resolve (refusal "Nobody answered the secret ask in 10 minutes. Nothing ran."))))
                                             secret-ms)}))))))

;; Tools ------------------------------------------------------------------------------------------

(defn- text-result [text] {:content [{:type "text" :text text}]})

(defn current-scene [id]
  (or (:scene (st id)) (store/scene id) scene/empty-scene))

(defn- turn-id!
  "This turn's name in the picture history; a turn Claude started itself gets one made."
  [id]
  (or (:turn-id (st id))
      (let [t (str (random-uuid))] (swap-chat! id assoc :turn-id t) t)))

(defn- draw [id input]
  (let [prev (current-scene id)
        {s :scene error :error} (scene/apply-draw prev input)]
    (if error
      (refusal error)
      (do (swap-chat! id assoc :scene s)
          (store/save-scene! id s)
          (pictures/record! id (turn-id! id) prev s)
          (emit! id {:type "scene" :scene s})
          (emit! id {:type "pictures" :pictures (pictures/listing id)})
          ;; A screenshot of the picture as it looks now, so Claude sees its own drawing; one that
          ;; fails is said in the text, never the draw's failure.
          (let [text (str "Picture updated. " (scene/describe s))]
            (-> (render/screenshot s (:color (store/chat id)))
                (.then (fn [{:keys [^js png error]}]
                         (if png
                           {:content [{:type "text" :text text}
                                      {:type "image" :data (.toString png "base64") :mimeType "image/png"}]}
                           (text-result (str text "\n\n(No screenshot of the picture: " error ")")))))))))))

(defn- sh-out [cmd args]
  (js/Promise. (fn [resolve]
                 (cp/execFile cmd (clj->js args) #js {:timeout 10000}
                              (fn [err stdout] (resolve (when-not err (str stdout))))))))

(defn- set-folder [id p]
  (let [abs (path/resolve cwd (str/replace (str p) #"^~(?=/|$)" (os/homedir)))
        read #(-> (fsp/readFile (path/join abs %) "utf8") (.catch (fn [_] nil)))]
    (if-not (try (.isDirectory (fs/statSync abs)) (catch :default _ false))
      (text-result (str "No folder at " abs ". Nothing changed."))
      (do (store/update! id {:folder abs})
          (changed!)
          (-> (js/Promise.all #js [(read "CLAUDE.md") (read ".claude/CLAUDE.md") (sh-out "git" ["-C" abs "status" "-sb"])])
              (.then (fn [[md md2 status]]
                       (let [md (or md md2)]
                         (text-result
                          (str "Working folder is now " abs ". Run commands there (cd " abs " && ...).\n"
                               (if md
                                 (str "\n<claude-md path=\"" abs "/CLAUDE.md\">\n" (subs md 0 (min 6000 (count md))) "\n</claude-md>\n")
                                 "\n(no CLAUDE.md in this folder)\n")
                               "\n<git-status>\n"
                               (if status (str/join "\n" (take 40 (str/split-lines status))) "(not a git repository)")
                               "\n</git-status>"))))))))))

(defn- mcp-call [id {:keys [server tool args]}]
  (case [server tool]
    ["picture" "draw"] (draw id args)
    ["workspace" "set_folder"] (set-folder id (:path args))
    ["workspace" "run_with_secret"] (secret-ask id args)
    (refusal (str "No tool " server "/" tool "."))))

(defn- can-use
  "Every tool may run (bypass mode still asks for a few); AskUserQuestion becomes a question ask."
  [id {:keys [name input toolUseID]}]
  (if (= name "AskUserQuestion")
    (js/Promise.
     (fn [resolve]
       (add-ask! id {:ask {:id (or toolUseID (str (random-uuid))) :kind "question" :questions (:questions input)}
                     :input input :resolve resolve})))
    {:behavior "allow" :updatedInput input}))

(defn- on-rpc [{:keys [rpc id kind args]}]
  (-> (js/Promise.resolve)
      (.then (fn [] (case kind
                      "mcp" (mcp-call id args)
                      "canUseTool" (can-use id args)
                      {:continue true})))
      (.then (fn [v] (hostlink/send! {:op "reply" :rpc rpc :value v}))
             (fn [e] (hostlink/send! {:op "reply" :rpc rpc :error (message e)})))))

;; SDK messages -----------------------------------------------------------------------------------

(defn- turn-end! [id m]
  (let [ok (and (= "success" (:subtype m)) (not (:is_error m)))
        error (when-not (or ok (:stopping (st id)))
                (if (= "success" (:subtype m))
                  (str (:result m))
                  (or (some->> (:errors m) seq (str/join "\n")) (:subtype m))))]
    (store/update! id (cond-> {:ended (now)} ok (assoc :preview (plain-start (:result m)))))
    (drop-asks! id true "The turn ended.")
    (swap-chat! id assoc :partial nil :stopping false :stream-counts {} :final-counts (atom {}) :turn-id nil)
    (when error (emit! id {:type "error" :text error}))
    (set-running! id false)
    (changed!)))

(defn- on-stream [id e]
  (case (:type e)
    "message_start" (swap-chat! id assoc :mid (get-in e [:message :id]))
    "content_block_start"
    (when (= "text" (get-in e [:content_block :type]))
      (let [mid (:mid (st id))
            n (get-in (swap-chat! id update-in [:stream-counts mid] (fnil inc -1)) [id :stream-counts mid])
            msg {:id (str mid ":" n) :role "assistant" :text "" :at (now)}]
        (swap-chat! id assoc :partial msg)
        (note-msg! id msg)))
    "content_block_delta"
    (when (= "text_delta" (get-in e [:delta :type]))
      (when-let [p (:partial (st id))]
        (swap-chat! id update-in [:partial :text] str (get-in e [:delta :text]))
        (emit! id {:type "delta" :id (:id p) :text (get-in e [:delta :text])})))
    nil))

(defn- on-sdk [id m]
  (let [top (nil? (:parent_tool_use_id m))]
    ;; A turn Claude starts by itself (after a background task) is running too.
    (when (and top (#{"stream_event" "assistant"} (:type m)) (not (:running (st id))))
      (set-running! id true))
    (case (:type m)
      "stream_event" (when top (on-stream id (:event m)))
      "assistant" (when top
                    (let [counts (or (:final-counts (st id))
                                     (get-in (swap-chat! id assoc :final-counts (atom {})) [id :final-counts]))
                          folder (:folder (store/chat id))]
                      (swap-chat! id assoc :partial nil)
                      (doseq [msg (history/assistant-msgs (:message m) counts (now) folder)]
                        (note-msg! id msg))))
      "user" (when (and top (sequential? (get-in m [:message :content])))
               (doseq [b (get-in m [:message :content])
                       :when (and (= "tool_result" (:type b)) (:is_error b))
                       :let [msg (some #(when (= (:id %) (:tool_use_id b)) %) (:turn (st id)))]
                       :when msg]
                 (note-msg! id (assoc msg :error true))))
      "result" (turn-end! id m)
      "system" (when (= "compact_boundary" (:subtype m))
                 (note-msg! id {:id (:uuid m) :role "note" :at (now)
                                :text (str "Context compacted ("
                                           (js/Math.round (/ (or (get-in m [:compact_metadata :pre_tokens]) 0) 1000))
                                           "k tokens before).")}))
      nil)))

(defn- ended! [id error]
  (swap-chat! id assoc :live false :partial nil)
  (drop-asks! id false nil)
  (when error (emit! id {:type "error" :text error}))
  (set-running! id false))

(defn on-host-event [{:keys [ev id] :as e}]
  (case ev
    "sessions" (let [live (into {} (map (juxt :id :busy)) (:list e))]
                 ;; Sessions the host no longer holds (it restarted) have ended.
                 (doseq [[cid s] @state :when (and (:live s) (not (contains? live cid)))]
                   (ended! cid "The session host restarted; this chat's work stopped. Send a message to carry on."))
                 (doseq [[cid busy] live]
                   (swap-chat! cid assoc :live true)
                   (set-running! cid (boolean busy))))
    "msg" (on-sdk id (:msg e))
    "rpc" (on-rpc e)
    "ended" (ended! id (:error e))
    nil))

(defn on-host-lost
  "The host connection dropped: its rpcs are gone with it (a host that is still there sends them
  again on reconnect)."
  []
  (doseq [id (keys @state)] (drop-asks! id false nil)))

;; Page actions -----------------------------------------------------------------------------------

(defn- token []
  (let [f (.-CLAUDIA_TOKEN_FILE env)
        t (when f (try (str/trim (fs/readFileSync f "utf8")) (catch :default _ nil)))]
    (if (seq t) t (throw (js/Error. "No Claude token: CLAUDIA_TOKEN_FILE is unset, missing or empty.")))))

(defn- start-spec [id resume?]
  {:options {:cwd cwd
             ;; Read on every start, so a new token takes effect with the next session.
             :env (assoc (into {} (for [k (js-keys env)] [k (aget env k)])) "CLAUDE_CODE_OAUTH_TOKEN" (token))
             (if resume? :resume :sessionId) id
             :systemPrompt {:type "preset" :preset "claude_code" :append tools/system-append}
             :allowedTools tools/allowed-tools
             :permissionMode "bypassPermissions"
             :allowDangerouslySkipPermissions true
             :includePartialMessages true}
   :mcp tools/mcp
   :hooks {}})

(defn- ensure-session! [id]
  (if (:live (st id))
    (js/Promise.resolve nil)
    (-> (sdk/getSessionInfo id #js {:dir cwd})
        (.catch (fn [_] nil))
        (.then (fn [info]
                 (hostlink/send! {:op "start" :id id :spec (start-spec id (some? info))})
                 (swap-chat! id assoc :live true :final-counts (atom {})))))))

(defn send-text! [id text]
  (-> (ensure-session! id)
      (.then (fn []
               (let [uuid (str (random-uuid))]
                 (when-not (:running (st id)) (swap-chat! id assoc :turn [] :turn-id uuid))
                 (note-msg! id {:id uuid :role "user" :text text :at (now)})
                 (hostlink/send! {:op "send" :id id :text text :uuid uuid})
                 (set-running! id true))))
      (.catch (fn [e] (emit! id {:type "error" :text (message e)})))))

(defn new-chat!
  "Starts a chat with its first message; returns its id (the Claude Code session id)."
  [text folder]
  (let [id (str (random-uuid))
        used (frequencies (keep :color (store/chats)))
        least (apply min (map #(get used % 0) chat-colors))
        title (let [line (str/trim (str (first (str/split-lines (str/trim (str text))))))]
                (if (> (count line) 80) (str (subs line 0 79) "…") line))]
    (store/add! {:id id :title (if (seq title) title "New chat") :at (now)
                 :folder (when (seq folder) folder)
                 :color (rand-nth (filter #(= least (get used % 0)) chat-colors))})
    (changed!)
    (send-text! id text)
    id))

(defn stop! [id]
  (when (:running (st id))
    (swap-chat! id assoc :stopping true)
    (drop-asks! id true "The user stopped the turn.")
    (hostlink/send! {:op "interrupt" :id id})))

(defn snapshot
  "Promise of the chat frame: the session file's history with this turn's live Msgs laid over it."
  [id]
  (let [r (store/chat id)]
    (-> (history/load cwd id (:folder r))
        (.catch (fn [e] (js/console.error "history:" (message e)) []))
        (.then (fn [hist]
                 (let [s (st id)
                       turn (:turn s)
                       partial (:partial s)
                       by-id (into {} (map (juxt :id identity)) turn)
                       known (set (map :id hist))
                       msgs (into (mapv #(get by-id (:id %) %) hist) (remove #(known (:id %)) turn))]
                   {:type "chat" :chat id :title (:title r) :folder (:folder r)
                    :messages (if partial (mapv #(if (= (:id %) (:id partial)) partial %) msgs) msgs)
                    :scene (current-scene id)
                    :running (boolean (:running s))
                    :asks (vec (map :ask (vals (:asks s))))}))))))
