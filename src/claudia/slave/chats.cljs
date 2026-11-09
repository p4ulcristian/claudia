(ns claudia.slave.chats
  "Every chat of this profile: starting and resuming its session in the host, turning the SDK's
  messages into page events, the draw and workspace tools, questions and secret asks. The pure
  parts are in claudia.slave.chats.* (titles, view, turns, answers, session)."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [claudia.node.env :as env]
            [claudia.node.fs :as nfs]
            [claudia.shared.text :as text]
            [claudia.slave.chats.answers :as answers]
            [claudia.slave.chats.session :as session]
            [claudia.slave.chats.titles :as titles]
            [claudia.slave.chats.turns :as turns]
            [claudia.slave.chats.view :as view]
            [claudia.slave.codeforms :as codeforms]
            [claudia.slave.changes :as changes]
            [claudia.slave.history :as history]
            [claudia.slave.hostlink :as hostlink]
            [claudia.slave.meta :as meta]
            [claudia.slave.pictures :as pictures]
            [claudia.slave.projects :as projects]
            [claudia.slave.render :as render]
            [claudia.slave.scene :as scene]
            [claudia.slave.secret :as secret]
            [claudia.slave.shown :as shown]
            [claudia.slave.store :as store]
            [claudia.slave.tasks :as tasks]
            [claudia.slave.tools :as tools]
            [claudia.slave.worktree :as worktree]))

(def cwd (env/env "CLAUDIA_CWD" (path/join (os/homedir) "Work")))

;; A chat whose turn ended this long ago lets its Claude Code process go; the next message resumes it.
(def ^:private idle-ms (* 15 60 1000))
(def ^:private secret-ms (* 10 60 1000))

(def chat-colors ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan"
                  "sky" "blue" "indigo" "violet" "purple" "magenta" "pink" "brown"])

;; id -> what lives only while the runner runs:
;;   :live (the host holds a session) :running :stopping :scene :since (when the running turn began)
;;   :asks {ask id -> {:ask (as the page gets it) :resolve :input :keys :secret :timer :at :rpc}}
;;   :turn (this turn's Msgs, by arrival) :partial (the assistant text being streamed)
;;   :mid (the API message streaming) :stream-counts :final-counts (API message id -> text blocks)
;;   :idle (timer)
;;   :turn-id (the last sent message's uuid: the turn's name in the picture history, kept for turns
;;   Claude starts itself) :unsaved {uuid -> Msg} (sent, not in the session file yet)
;;   :settling :settle (timer) :stale (restart after this turn: other tools or instructions)
(defonce state (atom {}))

;; Set by the runner: where events go.
(defonce out (atom {:emit (fn [_ _]) :changed (fn [])}))

(defn- st [id] (get @state id))
(defn- swap-chat! [id f & args] (swap! state update id #(apply f (or % {}) args)))
(defn- emit! [id frame] ((:emit @out) id (assoc frame :chat id)))
(defn- changed! [] ((:changed @out)))

;; Colours and titles -----------------------------------------------------------------------------

(defn least-used-colour
  "A new chat's colour (src/colors.ts pickColor): random among the ones the other chats in the list
  (not archived, not this one) wear least; never ash, which is only picked by hand. nil when the
  profile's preference says new chats keep the default look."
  ([] (least-used-colour nil))
  ([except]
   (when (not= "none" (:chatColors (codeforms/prefs)))
     (rand-nth (view/least-worn chat-colors (store/chats) except)))))

(defn wake!
  "Writing to a passive (ash) chat makes it active again: the colour it had, else a fresh one."
  [id]
  (let [r (store/chat id)]
    (when (= "ash" (:color r))
      (store/update! id {:color (or (:prev-color r) (least-used-colour id) nil) :prev_color nil}))))

(defn refresh-title!
  "The chat's title read again from its session (after a turn, a rename). Promise."
  [id]
  (-> (sdk/getSessionInfo id #js {:dir cwd})
      (.then (fn [info]
               (when info
                 (let [t (titles/session-title info)]
                   (when (and (store/chat id) (not= t (:title (store/chat id))))
                     (store/update! id {:title t})
                     (changed!))))))
      (.catch (fn [_] nil))))

(defn refresh-titles!
  "Every chat's title from the session list (at the runner's start)."
  []
  (-> (sdk/listSessions #js {:dir cwd :includeWorktrees false})
      (.then (fn [sessions]
               (let [updates (titles/title-updates (store/chats) sessions)]
                 (doseq [[id t] updates] (store/update! id {:title t}))
                 (when (seq updates) (changed!)))))
      (.catch (fn [e] (js/console.error "titles:" (text/error-text e))))))

(defn rename!
  "The user renames the chat: the session's own custom title (runner.ts: renameSession), ≤ 200."
  [id title]
  (let [t (text/cut (str/trim (str title)) 200 "")]
    (when (seq t)
      (store/update! id {:title t})
      (changed!)
      (-> (sdk/renameSession id t #js {:dir cwd})
          (.catch (fn [e] (js/console.error (str "[" (subs id 0 8) "] rename: " (text/error-text e)))))))))

;; The chat list ----------------------------------------------------------------------------------

(defn- working? [id] (or (boolean (:running (st id))) (pos? (tasks/working id))))

(defn rows
  "The chat list (Row in PROTOCOL.md). For the hub's pushes and badge (runner.ts attentionFields): a
  chat is unseen when its last turn ended (not stopped) after the person last had it open and the
  work it left running has settled; lastTurn, runningFor and each ask's ago are times as ago (the
  hub's clock may differ)."
  []
  (let [t (js/Date.now)]
    (mapv #(assoc (view/row % (st (:id %)) (tasks/working (:id %)) t)
                  :project (projects/root-name (:folder %) cwd))
          (store/chats))))

(defn- note-msg!
  "A new or changed Msg of this turn: kept (so a chat opened mid-turn has it) and sent."
  [id msg]
  (swap-chat! id update :turn view/with-msg msg)
  (emit! id {:type "msg" :msg msg}))

(declare arm-idle! turn-id! restart-if-stale!)

(defn- idle-out!
  "The quiet while is over: the chat's Claude Code goes, unless work it started still runs."
  [id]
  (when (and (:live (st id)) (not (:running (st id))))
    (if (tasks/running-any? id)
      (arm-idle! id)
      (do (hostlink/send! {:op "close" :id id})
          (swap-chat! id assoc :live false)))))

(defn- arm-idle!
  "Its Claude Code goes after a quiet while, never while work it started still runs (that would end
  the work; settling arms it again once the last of it ended)."
  [id]
  (some-> (:idle (st id)) js/clearTimeout)
  (swap-chat! id assoc :idle (when-not (:running (st id)) (js/setTimeout #(idle-out! id) idle-ms))))

;; Settling (src/chat.ts watchWork): the last background task ended after Claude's turn. Its notice
;; usually starts a turn of Claude's own within a moment, so the chat counts as finished only when
;; none started meanwhile; a turn's init message extends the wait, for a first answer slow to come.
(def ^:private settle-ms 5000)
(def ^:private settle-init-ms 30000)

(defn- unsettle! [id]
  (some-> (:settle (st id)) js/clearTimeout)
  (swap-chat! id assoc :settle nil :settling false))

(defn- settled!
  "The settle wait is over: with nothing working, the chat is finished now."
  [id]
  (swap-chat! id assoc :settle nil :settling false)
  (when-not (working? id)
    ;; The last turn's time moves to this moment: whoever opened the chat while the agents worked
    ;; sees it done again, and the hub's push is news.
    (let [r (store/chat id)]
      (when (and r (pos? (or (:ended r) 0)) (not (:stopped r)))
        (store/update! id {:ended (js/Date.now)})))
    (arm-idle! id)
    (restart-if-stale! id)
    (changed!)))

(defn- settle! [id wait]
  (some-> (:settle (st id)) js/clearTimeout)
  (swap-chat! id assoc :settling true :settle (js/setTimeout #(settled! id) wait)))

(defn- set-running! [id running]
  (when (not= running (boolean (:running (st id))))
    (swap-chat! id assoc :running running)
    (when running
      (swap-chat! id assoc :since (js/Date.now))
      (unsettle! id)
      (changes/begin-turn! id (turn-id! id) (:folder (store/chat id))))
    (store/update! id {:at (js/Date.now)})
    ;; stopped: the turn the person stopped (the page says "Stopped.").
    (emit! id (cond-> {:type "turn" :running running}
                (not running) (assoc :stopped (boolean (:stopping (st id))))))
    (changed!))
  (arm-idle! id)
  (when-not running (restart-if-stale! id)))

;; Asks -------------------------------------------------------------------------------------------

(defn- add-ask! [id entry]
  (swap-chat! id assoc-in [:asks (get-in entry [:ask :id])] (assoc entry :at (js/Date.now)))
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

(defn- drop-asks!
  "Ends every open ask of a chat; answer? false when nobody is left to answer (the host went away and
  sends its rpcs again)."
  [id answer? why]
  (doseq [ask-id (keys (:asks (st id)))
          :let [entry (take-ask! id ask-id)]]
    (when answer?
      ((:resolve entry) (answers/dropped-answer (get-in entry [:ask :kind]) why)))))

(defn answer!
  "An answer to a question ask; on a permit ask (ExitPlanMode) it is the OK."
  [id ask-id chosen]
  (when-let [entry (get-in (st id) [:asks ask-id])]
    (case (get-in entry [:ask :kind])
      "question"
      (do (take-ask! id ask-id)
          ((:resolve entry) {:behavior "allow" :updatedInput (assoc (:input entry) :answers chosen)})
          (let [said (history/answer-text (get-in entry [:ask :questions]) chosen)]
            (when (seq said)
              (note-msg! id {:id (str "answer-" ask-id) :role "user" :text said :at (js/Date.now)}))))
      "permit"
      (do (take-ask! id ask-id)
          ((:resolve entry) {:behavior "allow" :updatedInput (:input entry)}))
      nil)))

(defn deny! [id ask-id who]
  (when-let [entry (take-ask! id ask-id)]
    (if (= "secret" (get-in entry [:ask :kind]))
      (do (js/console.log (str "secret " (get-in entry [:ask :ref]) " (" (get-in entry [:ask :reason]) ") denied by " who))
          ((:resolve entry) (answers/refusal "The user said no to this secret. Nothing ran.")))
      ((:resolve entry) {:behavior "deny" :message "The user said no."}))))

(defn- run-secret!
  "The opened secret's one command; its output resolves the ask."
  [id ask-id entry value who]
  (let [{:keys [command env_name]} (:secret entry)
        {:keys [ref reason]} (:ask entry)]
    (take-ask! id ask-id)
    (js/console.log (str "secret " ref " (" reason ") allowed by " who))
    (-> (secret/run command env_name value (or (:folder (store/chat id)) cwd))
        (.then (fn [{:keys [error] :as result}]
                 ((:resolve entry) (cond-> (answers/text-result (:text result))
                                     error (assoc :isError true))))))))

(defn secret!
  "The sealed value for a secret ask: opened, used by its one command, dropped."
  [id ask-id sealed who]
  (when-let [entry (get-in (st id) [:asks ask-id])]
    (when (= "secret" (get-in entry [:ask :kind]))
      (let [value (try (secret/open sealed (:keys entry)) (catch :default _ nil))]
        (if-not (seq value)
          (emit! id {:type "error" :text "That secret did not open (sealed to another request?). The ask is still open."})
          (run-secret! id ask-id entry value who))))))

(defn- secret-expired!
  "Nobody answered a secret ask in time: it ends, refused."
  [id ask-id {:keys [ref reason]} done]
  (when (take-ask! id ask-id)
    (js/console.log (str "secret " ref " (" reason ") expired"))
    (done (answers/refusal "Nobody answered the secret ask in 10 minutes. Nothing ran."))))

(defn- secret-ask [id {:keys [env_name command] :as args}]
  (or (answers/secret-refusal args)
      (js/Promise.
       (fn [done]
         (let [kp (secret/keypair)
               ask-id (str (random-uuid))
               ask (answers/secret-ask ask-id args (:pubkey kp) (env/env "CLAUDIA_OP_ACCOUNT" "")
                                       (env/env "CLAUDIA_VAULT_SERVER" "") (+ (js/Date.now) secret-ms))]
           (add-ask! id {:ask ask :resolve done :keys kp
                         :secret {:command command :env_name env_name}
                         :timer (js/setTimeout #(secret-expired! id ask-id args done) secret-ms)}))))))

;; Tools ------------------------------------------------------------------------------------------

(defn current-scene [id]
  (or (:scene (st id)) (store/scene id) scene/empty-scene))

(defn- turn-id!
  "The turn's name in the picture history: the last sent message's uuid; a chat Claude works in
  before anything was sent here (after a runner start) gets one made."
  [id]
  (or (:turn-id (st id))
      (let [t (or (store/kv id "turn") (str (random-uuid)))]
        (swap-chat! id assoc :turn-id t)
        (store/kv! id "turn" t)
        t)))

(defn set-scene!
  "A chat's picture changed by the page (the editor): kept, saved (also as the picture
  of the turn that drew it, src/chat.ts userBlock), sent."
  [id s]
  (swap-chat! id assoc :scene s)
  (store/save-scene! id s)
  (when-let [newest (peek (store/pictures id))]
    (store/save-picture! id (:turn newest) s (:at newest)))
  (emit! id {:type "scene" :scene s :changed [] :turn (turn-id! id)}))

(defn chat-folder [id] (or (:folder (store/chat id)) cwd))

(defn scene-of
  "A chat's current picture, or nil when there is no such chat."
  [id]
  (when (store/chat id) (current-scene id)))

(defn- draw [id input]
  (let [prev (current-scene id)
        {s :scene changed :changed error :error summary :text} (codeforms/draw id prev input (chat-folder id))]
    (if error
      (answers/text-result error)
      (let [turn (turn-id! id)]
        (swap-chat! id assoc :scene s)
        (store/save-scene! id s)
        (pictures/record! id turn prev s)
        (emit! id {:type "scene" :scene s :changed changed :turn turn})
        (emit! id {:type "pictures" :pictures (pictures/listing id)})
        (-> (render/screenshot s (:color (store/chat id)))
            (.then #(answers/draw-answer summary %)))))))

(defn- sh-out [cmd args]
  (js/Promise. (fn [done]
                 (cp/execFile cmd (clj->js args) #js {:timeout 10000}
                              (fn [err stdout] (done (when-not err (str stdout))))))))

(defn- read-start
  "Promise of the start (6000 characters) of a file in folder, \"\" when there is none."
  [folder f]
  (-> (fsp/readFile (path/join folder f) "utf8")
      (.then #(text/cut % 6000 ""))
      (.catch (fn [_] ""))))

(defn- set-folder
  "set_folder (src/chat.ts folderTool): the folder the chat works in, its CLAUDE.md and git status."
  [id p]
  (let [folder (path/resolve cwd (answers/home-path p (os/homedir)))]
    (if-not (nfs/dir? folder)
      (answers/text-result (str "No folder at " folder ". Nothing changed."))
      (do (when (not= folder (:folder (store/chat id)))
            (store/update! id {:folder folder})
            (changes/folder-set! id folder (:running (st id)))
            (changed!))
          (-> (js/Promise.all #js [(read-start folder "CLAUDE.md") (read-start folder ".claude/CLAUDE.md")
                                   (sh-out "git" ["-C" folder "status" "--short" "--branch"])])
              (.then (fn [[md md2 status]]
                       (answers/folder-answer folder (or (not-empty md) (not-empty md2)) status))))))))

(defn move-folder!
  "The person moves the chat to another project: p (absolute, ~/…, or relative to the working folder)
  becomes its folder, blank: no folder. Claude hears of it with the next message (take-moved-note!).
  An error frame when there is no folder at p."
  [id p]
  (let [folder (when-not (str/blank? p) (path/resolve cwd (answers/home-path (str/trim p) (os/homedir))))]
    (cond
      (and folder (not (nfs/dir? folder))) (emit! id {:type "error" :text (str "No folder at " folder ".")})
      (= folder (:folder (store/chat id))) nil
      :else (do (store/update! id {:folder folder})
                (store/kv! id "moved" {:folder folder})
                (when folder (changes/folder-set! id folder (:running (st id))))
                (changed!)))))

(defn- take-moved-note!
  "The text with the note of the person's last move of the chat (move-folder!) after it, said once."
  [id text]
  (if-let [{:keys [folder]} (store/kv id "moved")]
    (do (store/kv! id "moved" nil)
        (str text "\n\n<system-reminder>" (answers/moved-note folder cwd) "</system-reminder>"))
    text))

(defn- show-image
  "An image file into the chat (src/chat.ts showImage): the result carries it, so it is saved with
  the session (the page shows it under the step) and Claude sees it too."
  [id {p :path caption :caption}]
  (let [file-path (path/resolve (or (:folder (store/chat id)) cwd) (answers/home-path p (os/homedir)))
        mime (get answers/image-types (str/lower-case (path/extname file-path)))
        stat (try (let [s (fs/statSync file-path)] {:file? (.isFile s) :size (.-size s)}) (catch :default _ nil))]
    (or (answers/image-refusal file-path mime stat)
        (answers/image-shown (path/basename file-path) (.toString (fs/readFileSync file-path) "base64") mime caption))))

(defn- show-video
  "A video file into the chat: copied into the runner's data folder (claudia.slave.shown), the
  result names the copy; the page plays it under the step. Claude gets the text only."
  [id {p :path caption :caption}]
  (let [file-path (path/resolve (or (:folder (store/chat id)) cwd) (answers/home-path p (os/homedir)))
        stat (try (let [s (fs/statSync file-path)] {:file? (.isFile s) :size (.-size s)}) (catch :default _ nil))]
    (if-let [why (shown/video-refusal file-path stat)]
      (answers/refusal why)
      (-> (shown/store! id file-path)
          (.then #(answers/text-result (shown/video-answer (path/basename file-path) % caption)))
          (.catch #(answers/refusal (str "Could not keep the video: " (.-message %))))))))

(defn- mcp-call [id {:keys [server tool args]}]
  (case [server tool]
    ["picture" "draw"] (draw id args)
    ["picture" "templates"] (answers/text-result (codeforms/template-list))
    ["picture" "save_template"] (answers/text-result (codeforms/save-template! (current-scene id) args))
    ["workspace" "set_folder"] (set-folder id (:path args))
    ["workspace" "run_with_secret"] (secret-ask id args)
    ["workspace" "show_image"] (show-image id args)
    ["workspace" "show_video"] (show-video id args)
    (throw (js/Error. (str "No tool " server "/" tool)))))

(defn- ask! [id entry] (js/Promise. (fn [done] (add-ask! id (assoc entry :resolve done)))))

(defn- can-use
  "Whether a tool may run (src/chat.ts ask). Every tool may (the user, 2026-10-22: \"give claude all
  permission\"); bypass mode still sends a few calls here (its own safety checks): they are allowed
  and logged. AskUserQuestion becomes a question ask, ExitPlanMode a permit ask (the plan to OK)."
  [id rpc {tool :name :keys [input toolUseID title description]}]
  (let [ask-id (or toolUseID (str (random-uuid)))]
    (cond
      (= tool "AskUserQuestion")
      (ask! id {:ask {:id ask-id :kind "question" :questions (:questions input)} :input input :rpc rpc})
      (= tool "ExitPlanMode")
      (ask! id {:ask (answers/permit-ask ask-id tool input title description) :input input :rpc rpc})
      :else
      (do (js/console.log (str "[" (subs id 0 8) "] auto-allowed " tool ": "
                               (text/fit (js/JSON.stringify (clj->js input)) 300)))
          {:behavior "allow" :updatedInput input}))))

(defn- folder-from-edit!
  "Claude edited a file while the chat has no folder: the file's project (its git root, else its own
  folder) becomes the chat's folder, so its changes and logo follow."
  [id {:keys [tool_name tool_input]}]
  (let [p (or (:file_path tool_input) (:notebook_path tool_input))]
    (when (and (not= "Bash" tool_name) (string? p) (str/starts-with? p "/") (not (:folder (store/chat id))))
      (let [dir (path/dirname p)]
        (cp/execFile "git" #js ["-C" dir "rev-parse" "--show-toplevel"] #js {:timeout 10000}
                     (fn [err stdout]
                       (let [root (if err dir (or (not-empty (str/trim (str stdout))) dir))]
                         (when-not (:folder (store/chat id))
                           (store/update! id {:folder root})
                           (changed!)))))))))

(defn- on-hook [id {:keys [event input]}]
  (case event
    "PreToolUse" (changes/before-edit! id (:tool_input input))
    "PostToolUse" (do (worktree/report! (store/chat id) input)
                      (folder-from-edit! id input)
                      {:continue true})
    {:continue true}))

(defn- on-rpc [{:keys [rpc id kind args]}]
  (-> (js/Promise.resolve)
      (.then (fn [] (case kind
                      "mcp" (mcp-call id args)
                      "canUseTool" (can-use id rpc args)
                      "hook" (on-hook id args)
                      {:continue true})))
      (.then (fn [v] (hostlink/send! {:op "reply" :rpc rpc :value v}))
             (fn [e] (hostlink/send! {:op "reply" :rpc rpc :error (text/error-text e)})))))

(defn- rpc-cancelled!
  "The host says an rpc was aborted (the SDK gave up on a permission ask): its card goes."
  [rpc]
  (doseq [[id s] @state
          [ask-id entry] (:asks s)
          :when (= rpc (:rpc entry))]
    (take-ask! id ask-id)))

;; Agents, changes, meters -----------------------------------------------------------------------

(defn- folder-of [id] (:folder (store/chat id)))

(defn- token []
  (let [f (env/env "CLAUDIA_TOKEN_FILE")
        t (when f (try (str/trim (fs/readFileSync f "utf8")) (catch :default _ nil)))]
    (if (seq t) t (throw (js/Error. "No Claude token yet: add one in Settings (bottom left).")))))

;; claudia-progress (bin/) is on the PATH of every chat's commands.
(def ^:private bin-dir (path/resolve (if (exists? js/__dirname) js/__dirname (js/process.cwd)) ".." "bin"))

(defn- run-env
  "The environment chats run with: the runner's own, the token (read on every start, so a new token
  takes effect with the next session) and Claudia's helpers on the PATH."
  ([] (run-env (token)))
  ([t]
   (meta/use-token! t)
   (session/run-env (.-env js/process) t bin-dir)))

(defonce ^:private probe-timer (atom nil))

(defn probe-soon!
  "A turn ended or Claude Code reported a rate-limit change: read the exact numbers again soon."
  [ms]
  (some-> @probe-timer js/clearTimeout)
  (reset! probe-timer (js/setTimeout #(try (meta/probe! cwd (run-env)) (catch :default _ nil)) ms)))

(defonce ^:private agent-timers (atom {}))

(defn- agents-changed!
  "The chat's agent list to the page; the rows when its running count changed. The last agent
  ending with no turn running starts the settle."
  [id]
  (swap! agent-timers dissoc id)
  (tasks/refresh! cwd id (folder-of id))
  (let [n (tasks/working id)
        was (or (:agents-n (st id)) 0)]
    (emit! id {:type "agents" :agents (tasks/agents cwd id (folder-of id))})
    (when (not= n was)
      (swap-chat! id assoc :agents-n n)
      (cond (or (pos? n) (:running (st id))) (unsettle! id)
            (pos? was) (settle! id settle-ms))
      (changed!))
    (restart-if-stale! id)))

(defn- emit-agents!
  "The chat's agents changed: told a little later, once for a burst of task events."
  [id]
  (when-not (get @agent-timers id)
    (swap! agent-timers assoc id (js/setTimeout #(agents-changed! id) 250))))

(defn settings
  "The chat's model and effort (nil = the user's default), applied on its next start."
  [id]
  (merge {:model nil :effort nil} (select-keys (store/kv id "settings") [:model :effort])))

(defn set-settings! [id m]
  (let [s (merge (settings id) (select-keys m [:model :effort]))
        quiet #(.catch % (fn [_]))]
    (store/kv! id "settings" s)
    ;; A live session takes them now too.
    (when (:live (st id))
      (when (contains? m :model) (quiet (apply hostlink/control! id "setModel" (if (:model s) [(:model s)] []))))
      (when (contains? m :effort) (quiet (hostlink/control! id "applyFlagSettings" {:effortLevel (:effort s)}))))
    (emit! id {:type "settings" :settings s :model (:model (st id))})))

(defn- read-context!
  "The chat's context use (tokens of its window), after a turn and at its start."
  [id]
  (-> (hostlink/control! id "getContextUsage" {:detail "summary"})
      (.then (fn [c]
               (when-let [ctx (turns/context-of c)]
                 (swap-chat! id assoc :context ctx)
                 (store/kv! id "context" ctx)
                 (emit! id {:type "context" :context ctx}))))
      (.catch (fn [_] nil))))

(defn stop-task!
  "Stops one agent or background shell of the chat; a task whose agent was stopped never reports
  its end, so Stop always ends it here."
  [id task]
  (-> (hostlink/control! id "stopTask" task)
      (.catch (fn [_] nil))
      (.then #(tasks/stopped! id task))))

(defn agent-detail [id agent] (tasks/detail cwd id (folder-of id) agent))

(defn agent-git [id agent rev] (tasks/agent-git cwd id agent rev))

(defn change-texts [id change] (store/change-texts id change))

;; Sessions: tools and instructions ---------------------------------------------------------------

(defn- prompt-version [] (session/hash36 (codeforms/prompt-for)))
(defn- tools-version [] (session/hash36 (js/JSON.stringify (clj->js (codeforms/mcp-for)))))

(defn- close-session! [id]
  (hostlink/send! {:op "close" :id id})
  (swap-chat! id assoc :live false :stale false)
  (tasks/session-gone! id))

(defn- restart-if-stale!
  "A session started with other tools (that couldn't be swapped) or other instructions starts again
  (resumed, same conversation) once its turn and the work it started are done."
  [id]
  (when (and (:stale (st id)) (:live (st id)) (not (:running (st id))) (not (tasks/running-any? id)))
    (close-session! id)))

(defn- swap-tools!
  "Hands a live session the tools as they stand now (src/chat.ts swapTools); one that refuses them
  starts again after its turn."
  [id]
  (when (:live (st id))
    (let [mcp (codeforms/mcp-for)
          v (tools-version)]
      (-> (hostlink/control! id "setMcp" mcp)
          (.then (fn [_] (store/kv! id "session" (assoc (store/kv id "session") :tools v)))
                 (fn [e]
                   (js/console.error (str "[" (subs id 0 8) "] tools not swapped (" (text/error-text e) "); restarting after this turn"))
                   (swap-chat! id assoc :stale true)
                   (restart-if-stale! id)))))))

(defn prefs-changed!
  "A form deleted or restored (the profile's prefs): every live chat gets the new draw tool now, and
  a new system prompt after its turn."
  []
  (doseq [[id s] @state :when (:live s)]
    (when (not= (prompt-version) (:prompt (store/kv id "session")))
      (swap-chat! id assoc :stale true)
      (restart-if-stale! id))
    ;; (still live: its turn or work runs; the tools now, the prompt after it)
    (swap-tools! id)))

(defn- watch-token!
  "Someone switched the token in Settings: the new plan's limits show now (runner.ts watch)."
  []
  (when-let [f (env/env "CLAUDIA_TOKEN_FILE")]
    (try (fs/watch (path/dirname f)
                   (fn [_ file]
                     (when (= file (path/basename f))
                       (try (meta/use-token! (token)) (catch :default _ nil)))))
         (catch :default e (js/console.error "token watch:" (text/error-text e))))))

(defn init!
  "Called once by the runner: agent lists follow their tasks, running agents' steps and shells'
  progress are read every 2 s, the models and commands are probed, and titles are read."
  []
  (reset! tasks/on-change emit-agents!)
  (js/setInterval (fn []
                    (doseq [id (tasks/chats-running)]
                      (when (tasks/refresh! cwd id (folder-of id)) (emit-agents! id))))
                  2000)
  (refresh-titles!)
  (try (meta/probe! cwd (run-env))
       (catch :default e (js/console.error "meta:" (text/error-text e))))
  (watch-token!))

;; SDK messages -----------------------------------------------------------------------------------

(defn- take-in!
  "Claude Code took a message sent mid-turn in (started) or dropped it (cancelled)."
  [id sent-id phase]
  (when-let [m (get-in (st id) [:unsaved sent-id])]
    (when (:queued m)
      (case phase
        "started" (let [m (dissoc m :queued)]
                    (swap-chat! id turns/taken-in m)
                    (emit! id {:type "msg" :msg m}))
        "cancelled" (do (swap-chat! id turns/dropped sent-id)
                        (emit! id {:type "msg" :msg {:id sent-id :role "user" :gone true}}))
        nil))))

(defn- turn-end! [id m]
  (let [stopped (boolean (:stopping (st id)))
        {:keys [error row]} (turns/turn-outcome m stopped (js/Date.now) (str (random-uuid)))]
    ;; How it ended, for the hub's pushes and badge: the result's uuid (one id per turn), when, how,
    ;; and the reply's start.
    (store/update! id row)
    (drop-asks! id true "The turn ended.")
    (swap-chat! id assoc :partial nil :stream-counts {} :final-counts {})
    (when (and error (not stopped)) (emit! id {:type "error" :text error}))
    ;; A turn that ended took in everything sent during it (a send not taken in starts the next).
    (doseq [[sent-id msg] (:unsaved (st id)) :when (:queued msg)] (take-in! id sent-id "started"))
    (set-running! id false)
    (swap-chat! id assoc :stopping false)
    (changed!)
    (read-context! id)
    (refresh-title! id)
    (probe-soon! 8000)
    (-> (changes/finish! id)
        (.then (fn [changed-rows] (when (seq changed-rows) (emit! id {:type "changes" :changes changed-rows})))))))

(defn- stream-text-start!
  "A text block starts streaming: a new assistant Msg (its id numbered like the session file's)."
  [id]
  (emit! id {:type "status" :running true})
  (let [mid (:mid (st id))
        n (get-in (swap-chat! id update-in [:stream-counts mid] (fnil inc -1)) [id :stream-counts mid])
        msg {:id (str mid ":" n) :role "assistant" :text "" :at (js/Date.now)}]
    (swap-chat! id assoc :partial msg)
    (note-msg! id msg)))

(defn- on-stream [id e]
  (case (:type e)
    "message_start" (swap-chat! id assoc :mid (get-in e [:message :id]))
    "content_block_start"
    (do (when (= "thinking" (get-in e [:content_block :type])) (emit! id {:type "status" :running true :note "Thinking"}))
        (when (= "text" (get-in e [:content_block :type])) (stream-text-start! id)))
    "content_block_delta"
    (when (= "text_delta" (get-in e [:delta :type]))
      (when-let [p (:partial (st id))]
        (swap-chat! id update-in [:partial :text] str (get-in e [:delta :text]))
        (emit! id {:type "delta" :id (:id p) :text (get-in e [:delta :text])})))
    nil))

(defn- on-assistant! [id m]
  (let [[msgs counts] (history/read-assistant (:message m) (or (:final-counts (st id)) {}) (js/Date.now)
                                              (:folder (store/chat id)))]
    (swap-chat! id assoc :partial nil :final-counts counts)
    (tasks/note-calls! id (:message m))
    (doseq [msg msgs] (note-msg! id msg))))

(defn- on-user!
  "A user message of the turn: a background task's notice (when Claude Code hands it on as one),
  tool results onto their steps."
  [id m]
  (let [c (get-in m [:message :content])]
    (when-let [notice (history/task-notice (history/strip-reminders (turns/user-text c)))]
      (note-msg! id {:id (or (:uuid m) (str "notice-" (js/Date.now))) :role "note" :text notice :at (js/Date.now)}))
    (doseq [b (turns/tool-results c)
            :let [msg (some #(when (= (:id %) (:tool_use_id b)) %) (:turn (st id)))]
            :when msg]
      (note-msg! id (turns/with-result msg b)))))

(defn- on-init!
  "A turn is starting (init comes ahead of its messages): a settling chat waits for it."
  [id m]
  (when (:settling (st id)) (settle! id settle-init-ms))
  (swap-chat! id assoc :model (:model m) :commands (vec (:slash_commands m)))
  (emit! id {:type "settings" :settings (settings id) :model (:model m)
             :commands (vec (:slash_commands m))})
  (read-context! id))

(defn- on-task-system!
  "A task message; a background task's end becomes its notice, as the session file keeps it."
  [id m]
  (tasks/load! id true)
  (tasks/on-system! id m)
  (when (and (turns/task-notice? m)
             ;; An agent's own shells and agents are part of it: their notices are its.
             (not (tasks/child? id (:task_id m)))
             ;; A foreground agent's answer is its tool result, not a notice.
             (tasks/background? id (:task_id m)))
    (note-msg! id {:id (str "task-" (:task_id m) "-" (js/Date.now)) :role "note" :text (:summary m) :at (js/Date.now)})))

(defn- on-system-msg! [id m]
  (case (:subtype m)
    "compact_boundary" (do (note-msg! id (turns/compact-note m (js/Date.now)))
                           (read-context! id))
    "init" (on-init! id m)
    "api_retry" (emit! id {:type "status" :running true :note "Retrying…"})
    "status" (when (= "compacting" (:status m)) (emit! id {:type "status" :running true :note "Compacting"}))
    ;; A slash command's output, and Claude Code's warnings and notices.
    "local_command_output" (note-msg! id (turns/note-of m "out-" (js/Date.now)))
    "informational" (when (#{"warning" "notice"} (:level m))
                      (note-msg! id (turns/note-of m "info-" (js/Date.now))))
    (on-task-system! id m)))

(defn- on-sdk [id m]
  (let [top (nil? (:parent_tool_use_id m))]
    ;; A turn Claude starts by itself (after a background task) is running too.
    (when (and top (#{"stream_event" "assistant"} (:type m)) (not (:running (st id))))
      (set-running! id true))
    (case (:type m)
      "stream_event" (when top (on-stream id (:event m)))
      "assistant" (when top (on-assistant! id m))
      "user" (when top (on-user! id m))
      "result" (turn-end! id m)
      "command_lifecycle" (take-in! id (:command_uuid m) (:state m))
      "system" (on-system-msg! id m)
      "rate_limit_event" (do (try (meta/note-limits! (token) (:rate_limit_info m))
                                  (catch :default _ nil))
                             (probe-soon! 4000))
      nil)))

(defn- ended! [id error]
  (swap-chat! id assoc :live false :partial nil)
  (tasks/session-gone! id)
  (drop-asks! id false nil)
  (when error (emit! id {:type "error" :text error}))
  (set-running! id false))

(defn- session-held!
  "The host holds the chat's session (busy: its turn runs). After a runner start: the runner's tools
  or instructions changed since this session started (an update): the new tools now, the
  instructions after its turn."
  [cid busy]
  (let [was (:live (st cid))
        kept (store/kv cid "session")]
    (tasks/load! cid true)
    (swap-chat! cid assoc :live true :account (:account kept))
    (when busy (swap-chat! cid assoc :since (or (:since (st cid)) (js/Date.now))))
    (set-running! cid (boolean busy))
    (when-not was
      (when (not= (:tools kept) (tools-version)) (swap-tools! cid))
      (when (not= (:prompt kept) (prompt-version))
        (swap-chat! cid assoc :stale true)
        (restart-if-stale! cid)))))

(defn- on-sessions!
  "The sessions the host holds: those it no longer holds (it restarted) have ended."
  [sessions]
  (let [live (into {} (map (juxt :id :busy)) sessions)]
    (doseq [[cid s] @state :when (and (:live s) (not (contains? live cid)))]
      (ended! cid "The session host restarted; this chat's work stopped. Send a message to carry on."))
    (doseq [[cid busy] live] (session-held! cid busy))))

(defn on-host-event [{:keys [ev id] :as e}]
  (case ev
    "sessions" (on-sessions! (:list e))
    "msg" (on-sdk id (:msg e))
    "rpc" (on-rpc e)
    "cancel" (rpc-cancelled! (:rpc e))
    "ended" (ended! id (:error e))
    nil))

(defn on-host-lost
  "The host connection dropped: its rpcs are gone with it (a host that is still there sends them
  again on reconnect)."
  []
  (doseq [id (keys @state)] (drop-asks! id false nil)))

;; Page actions -----------------------------------------------------------------------------------

(defn- start-spec [id resume? child-env]
  (let [s (settings id)]
    (session/start-spec {:cwd cwd :id id :resume? resume? :env child-env
                         :prompt (codeforms/prompt-for) :allowed tools/allowed-tools
                         :model (:model s) :effort (:effort s) :mcp (codeforms/mcp-for)
                         :edit-tools changes/edit-tools :worktrees? (worktree/on?)})))

(defn- start-session!
  "Starts (resume?: resumes) the chat's Claude Code in the host on token t (account: its name)."
  [id resume? t account]
  (hostlink/send! {:op "start" :id id :spec (start-spec id resume? (run-env t))})
  (codeforms/forms-seen-now! id)
  (store/kv! id "session" {:account account :tools (tools-version) :prompt (prompt-version)})
  (swap-chat! id assoc :live true :account account :stale false :final-counts {}))

(defn- ensure-session!
  "The chat's Claude Code, started (or resumed) on the token in use. The token was replaced since its
  session started: it starts again on the new one (not while work it started still runs, which that
  would end)."
  [id]
  (let [t (token)
        account (session/account-of t)]
    (when (and (:live (st id)) (not (:running (st id))) (zero? (tasks/working id))
               (some? (:account (st id))) (not= account (:account (st id))))
      (close-session! id))
    (if (:live (st id))
      (js/Promise.resolve nil)
      (-> (sdk/getSessionInfo id #js {:dir cwd})
          (.catch (fn [_] nil))
          (.then (fn [info] (start-session! id (some? info) t account)))))))

(defn- send-now!
  "The message into the live session; sent while a turn runs, it is queued until Claude takes it in."
  [id message images was-live?]
  (let [msg (turns/sent-msg (str (random-uuid)) message images (boolean (:running (st id))) (js/Date.now))]
    (swap-chat! id turns/with-sent msg)
    (store/kv! id "turn" (:id msg))
    (note-msg! id msg)
    ;; The forms news goes with this message, as a reminder the chat doesn't show (only to a
    ;; session that ran with the old tools).
    (hostlink/send! (cond-> {:op "send" :id id :text (take-moved-note! id (codeforms/take-note! id message was-live?)) :uuid (:id msg)}
                      (seq images) (assoc :images images)))
    (set-running! id true)))

(defn send-text!
  "A message into the chat; images: [{media_type data}] sent before the text (photos, video frames).
  Sent while a turn runs, it is queued until Claude takes it in."
  [id message & [images]]
  (-> (js/Promise.resolve)
      (.then (fn []
               (let [live? (boolean (:live (st id)))]
                 (.then (ensure-session! id) (fn [] live?)))))
      (.then #(send-now! id message images %))
      (.catch (fn [e] (emit! id {:type "error" :text (text/error-text e)})))))

(defn new-chat!
  "Starts a chat with its first message; returns its id (the Claude Code session id)."
  [message folder & [images]]
  (let [id (str (random-uuid))]
    (store/add! {:id id :title (titles/first-sent-title message) :at (js/Date.now)
                 :folder (when (seq folder) folder)
                 :color (least-used-colour)})
    (changed!)
    (send-text! id message images)
    id))

(defn stop! [id]
  (drop-asks! id true "The user stopped the turn.")
  (when (:running (st id))
    (swap-chat! id assoc :stopping true))
  (hostlink/send! {:op "interrupt" :id id}))

(defn- chat-frame
  "The chat frame from the session file's history: sent messages it holds now are no longer kept
  aside."
  [id r hist]
  (swap-chat! id update :unsaved view/unsaved-after (set (map :id hist)))
  (let [s (st id)]
    (tasks/load! id (boolean (:live s)))
    {:type "chat" :chat id :title (:title r) :folder (:folder r)
     :agents (tasks/agents cwd id (:folder r))
     :changes (store/changes id)
     :settings (settings id)
     :model (:model s)
     :commands (:commands s)
     :context (or (:context s) (store/kv id "context"))
     :messages (view/chat-msgs hist s)
     :scene (current-scene id)
     :running (boolean (:running s))
     :since (:since s)
     :asks (vec (map :ask (vals (:asks s))))}))

(defn snapshot
  "Promise of the chat frame: the session file's history with this turn's live Msgs and the sent
  messages the file doesn't hold yet laid over it."
  [id]
  (let [r (store/chat id)]
    (-> (history/load cwd id (:folder r))
        (.catch (fn [e] (js/console.error "history:" (text/error-text e)) []))
        (.then #(chat-frame id r %)))))
