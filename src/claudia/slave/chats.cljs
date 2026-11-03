(ns claudia.slave.chats
  "Every chat of this profile: starting and resuming its session in the host, turning the SDK's
  messages into page events, the draw and workspace tools, questions and secret asks."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            [clojure.string :as str]
            [claudia.shared.files :as files]
            [claudia.slave.codeforms :as codeforms]
            [claudia.slave.changes :as changes]
            [claudia.slave.history :as history]
            [claudia.slave.hostlink :as hostlink]
            [claudia.slave.meta :as meta]
            [claudia.slave.pictures :as pictures]
            [claudia.slave.render :as render]
            [claudia.slave.scene :as scene]
            [claudia.slave.secret :as secret]
            [claudia.slave.store :as store]
            [claudia.slave.tasks :as tasks]
            [claudia.slave.tools :as tools]
            [claudia.slave.worktree :as worktree]))

(def ^:private env (.-env js/process))

(def cwd (or (.-CLAUDIA_CWD env) (path/join (os/homedir) "Work")))

;; A chat whose turn ended this long ago lets its Claude Code process go; the next message resumes it.
(def ^:private idle-ms (* 15 60 1000))
(def ^:private secret-ms (* 10 60 1000))

(def chat-colors ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan"
                  "sky" "blue" "indigo" "violet" "purple" "magenta" "pink" "brown"])

;; id -> what lives only while the runner runs:
;;   :live (the host holds a session) :running :stopping :scene :since (when the running turn began)
;;   :asks {ask id -> {:ask (as the page gets it) :resolve :input :keys :secret :timer :at :rpc}}
;;   :turn (this turn's Msgs, by arrival) :partial (the assistant text being streamed)
;;   :mid (the API message streaming) :stream-counts :final-counts :idle (timer)
;;   :turn-id (the last sent message's uuid: the turn's name in the picture history, kept for turns
;;   Claude starts itself) :unsaved {uuid -> Msg} (sent, not in the session file yet)
;;   :settling :settle (timer) :stale (restart after this turn: other tools or instructions)
(defonce state (atom {}))

;; Set by the runner: where events go.
(defonce out (atom {:emit (fn [_ _]) :changed (fn [])}))

(defn- now [] (js/Date.now))
(defn- message [e] (str (or (some-> e .-message) e)))
(defn- st [id] (get @state id))
(defn- swap-chat! [id f & args] (swap! state update id #(apply f (or % {}) args)))
(defn- emit! [id frame] ((:emit @out) id (assoc frame :chat id)))
(defn- changed! [] ((:changed @out)))

(defn- re-replace [s pattern flags to] (.replace (str s) (js/RegExp. pattern flags) to))

(defn plain-start
  "The start of a reply as plain text (src/chat.ts plainStart: a phone notification's body, the chat
  list's preview): markdown marks taken out, code blocks kept as their text, links as their words,
  all on one line, cut at a word."
  [md max]
  (let [text (-> (history/strip-reminders md)
                 (re-replace "^\\s*(```|~~~).*$" "gm" "")
                 (re-replace "!\\[([^\\]]*)\\]\\([^)]*\\)" "g" "$1")
                 (re-replace "\\[([^\\]]+)\\]\\([^)]*\\)" "g" "$1")
                 (re-replace "^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+(\\[[ xX]\\]\\s+)?|\\d+[.)]\\s+)" "gm" "")
                 (re-replace "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$" "gm" "")
                 (re-replace "\\|" "g" " ")
                 (re-replace "(\\*\\*|__|~~)(.+?)\\1" "g" "$2")
                 (re-replace "(^|[^\\w*])[*_]([^*_\\n]+)[*_](?!\\w)" "g" "$1$2")
                 (re-replace "`([^`]*)`" "g" "$1")
                 (re-replace "<[^>\\n]+>" "g" "")
                 (re-replace "\\s+" "g" " ")
                 str/trim)
        chars (js/Array.from text)]
    (if (<= (.-length chars) max)
      text
      (let [cut (.join (.slice chars 0 (dec max)) "")
            word (re-replace cut "\\s+\\S*$" "" "")]
        (str (if (> (count word) (/ max 2)) word cut) "…")))))

;; Colours and titles -----------------------------------------------------------------------------

(defn least-used-colour
  "A new chat's colour (src/colors.ts pickColor): random among the ones the other chats in the list
  (not archived, not this one) wear least; never ash, which is only picked by hand. nil when the
  profile's preference says new chats keep the default look."
  ([] (least-used-colour nil))
  ([except]
   (when (not= "none" (:chatColors (codeforms/prefs)))
     (let [used (frequencies (keep #(when (and (not (:archived %)) (not= except (:id %))) (:color %)) (store/chats)))
           least (apply min (map #(get used % 0) chat-colors))]
       (rand-nth (filter #(= least (get used % 0)) chat-colors))))))

(defn wake!
  "Writing to a passive (ash) chat makes it active again: the colour it had, else a fresh one."
  [id]
  (let [r (store/chat id)]
    (when (= "ash" (:color r))
      (store/update! id {:color (or (:prev-color r) (least-used-colour id) nil) :prev_color nil}))))

(defn first-prompt-title
  "A first message with attached files (runner.ts firstPromptTitle): its text, else the first file's
  name (the SDK may have cut the message short inside a tag)."
  [first-prompt]
  (let [p (str first-prompt)
        at (str/index-of p "<claudia-file ")]
    (if-not at
      p
      (or (not-empty (str/trim (subs p 0 at)))
          (not-empty (:name (first (:files (files/split-files p)))))
          (second (re-find #"\bname=\"([^\"]*)\"" p))
          ""))))

(defn session-title
  "A chat's title from its session (runner.ts shownRows): the custom title, the summary, the first
  prompt, or \"New chat\". (The SDK's summary falls back to the first prompt; then the first prompt's
  claudia-file tags are read as above.)"
  [^js info]
  (let [custom (not-empty (.-customTitle info))
        summary (not-empty (.-summary info))
        first-prompt (not-empty (.-firstPrompt info))]
    (or custom
        (when (and summary (not= summary first-prompt)) summary)
        (not-empty (first-prompt-title first-prompt))
        summary
        "New chat")))

(defn first-sent-title
  "The title of a chat whose session file isn't written yet (src/chat.ts send): the first line of
  the first message (120 characters), else its first file's name, else \"New chat\"."
  [text]
  (let [{t :text fs :files} (files/split-files (str text))
        line (let [l (first (str/split-lines (str/trim t)))] (subs (str l) 0 (min 120 (count (str l)))))]
    (or (not-empty line) (not-empty (:name (first fs))) "New chat")))

(defn refresh-title!
  "The chat's title read again from its session (after a turn, a rename). Promise."
  [id]
  (-> (sdk/getSessionInfo id #js {:dir cwd})
      (.then (fn [info]
               (when info
                 (let [t (session-title info)]
                   (when (and (store/chat id) (not= t (:title (store/chat id))))
                     (store/update! id {:title t})
                     (changed!))))))
      (.catch (fn [_] nil))))

(defn refresh-titles!
  "Every chat's title from the session list (at the runner's start)."
  []
  (-> (sdk/listSessions #js {:dir cwd :includeWorktrees false})
      (.then (fn [list]
               (let [have (into {} (map (juxt :id identity)) (store/chats))
                     n (reduce (fn [n ^js s]
                                 (let [r (get have (.-sessionId s))
                                       t (session-title s)]
                                   (if (and r (not= t (:title r)))
                                     (do (store/update! (:id r) {:title t}) (inc n))
                                     n)))
                               0 list)]
                 (when (pos? n) (changed!)))))
      (.catch (fn [e] (js/console.error "titles:" (message e))))))

(defn rename!
  "The user renames the chat: the session's own custom title (runner.ts: renameSession), ≤ 200."
  [id title]
  (let [t (str/trim (str title))
        t (subs t 0 (min 200 (count t)))]
    (when (seq t)
      (store/update! id {:title t})
      (changed!)
      (-> (sdk/renameSession id t #js {:dir cwd})
          (.catch (fn [e] (js/console.error (str "[" (subs id 0 8) "] rename: " (message e)))))))))

;; The chat list ----------------------------------------------------------------------------------

(defn- working? [id] (or (boolean (:running (st id))) (pos? (tasks/working id))))

(defn rows
  "The chat list (Row in PROTOCOL.md). For the hub's pushes and badge (runner.ts attentionFields): a
  chat is unseen when its last turn ended (not stopped) after the person last had it open and the
  work it left running has settled; lastTurn, runningFor and each ask's ago are times as ago (the
  hub's clock may differ)."
  []
  (let [t (now)]
    (vec (for [r (store/chats)
               :let [s (st (:id r))
                     entries (vals (:asks s))
                     asks (map :ask entries)
                     question (some #(get-in % [:questions 0 :question]) asks)
                     agents (tasks/working (:id r))
                     working (or (boolean (:running s)) (pos? agents))
                     ended (or (:ended r) 0)]]
           {:id (:id r) :title (:title r) :folder (:folder r) :color (:color r)
            :running (boolean (:running s))
            :waiting (boolean (seq asks))
            :at (:at r)
            :preview (or question
                         (some #(case (:kind %)
                                  "secret" (str "Secret: " (:reason %))
                                  "permit" (str "Needs your permission: " (:tool %))
                                  nil)
                               asks)
                         (:preview r))
            ;; Work Claude started still runs: not done yet (README decision 25).
            :agents agents
            :settling (boolean (:settling s))
            :unseen (boolean (and (pos? ended) (not (:stopped r)) (not working) (not (:settling s))
                                  (> ended (or (:seen r) 0))))
            :archived (:archived r)
            ;; For the hub's phone alerts: when the last turn ended and how, and the open asks.
            :ended ended
            :lastTurn (when (pos? ended)
                        {:id (or (:last-turn r) (str "ended-" ended)) :ago (- t ended)
                         :stopped (:stopped r) :failed (:failed r) :reply (:reply r)})
            :runningFor (when (and working (:since s)) (- t (:since s)))
            :asks (vec (for [{a :ask at :at} entries]
                         {:id (:id a) :kind (:kind a) :ago (- t (or at t))
                          :text (case (:kind a)
                                  "secret" (:reason a)
                                  "permit" (str/join ": " (remove str/blank? [(:tool a) (:description a)]))
                                  (get-in a [:questions 0 :question]))}))}))))

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

(declare arm-idle! turn-id! restart-if-stale!)

(defn- arm-idle!
  "Its Claude Code goes after a quiet while, never while work it started still runs (that would end
  the work; settling arms it again once the last of it ended)."
  [id]
  (some-> (:idle (st id)) js/clearTimeout)
  (swap-chat! id assoc :idle
              (when-not (:running (st id))
                (js/setTimeout (fn []
                                 (when (and (:live (st id)) (not (:running (st id))))
                                   (if (tasks/running-any? id)
                                     (arm-idle! id)
                                     (do (hostlink/send! {:op "close" :id id})
                                         (swap-chat! id assoc :live false)))))
                               idle-ms))))

;; Settling (src/chat.ts watchWork): the last background task ended after Claude's turn. Its notice
;; usually starts a turn of Claude's own within a moment, so the chat counts as finished only when
;; none started meanwhile; a turn's init message extends the wait, for a first answer slow to come.
(def ^:private settle-ms 5000)
(def ^:private settle-init-ms 30000)

(defn- unsettle! [id]
  (some-> (:settle (st id)) js/clearTimeout)
  (swap-chat! id assoc :settle nil :settling false))

(defn- settle! [id wait]
  (some-> (:settle (st id)) js/clearTimeout)
  (swap-chat! id assoc :settling true
              :settle (js/setTimeout
                       (fn []
                         (swap-chat! id assoc :settle nil :settling false)
                         (when-not (working? id)
                           ;; The last turn's time moves to this moment: whoever opened the chat
                           ;; while the agents worked sees it done again, and the hub's push is news.
                           (let [r (store/chat id)]
                             (when (and r (pos? (or (:ended r) 0)) (not (:stopped r)))
                               (store/update! id {:ended (now)})))
                           (arm-idle! id)
                           (restart-if-stale! id)
                           (changed!)))
                       wait)))

(defn- set-running! [id running]
  (when (not= running (boolean (:running (st id))))
    (swap-chat! id assoc :running running)
    (when running
      (swap-chat! id assoc :since (now))
      (unsettle! id)
      (changes/begin-turn! id (turn-id! id) (:folder (store/chat id))))
    (store/update! id {:at (now)})
    (emit! id {:type "turn" :running running})
    (changed!))
  (arm-idle! id)
  (when-not running (restart-if-stale! id)))

;; Asks -------------------------------------------------------------------------------------------

(defn- add-ask! [id entry]
  (swap-chat! id assoc-in [:asks (get-in entry [:ask :id])] (assoc entry :at (now)))
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
      ((:resolve entry) (if (#{"question" "permit"} (get-in entry [:ask :kind]))
                          {:behavior "deny" :message why :interrupt true}
                          (refusal (str why " Nothing ran.")))))))

(defn answer!
  "An answer to a question ask; on a permit ask (ExitPlanMode) it is the OK."
  [id ask-id answers]
  (when-let [entry (get-in (st id) [:asks ask-id])]
    (case (get-in entry [:ask :kind])
      "question"
      (do (take-ask! id ask-id)
          ((:resolve entry) {:behavior "allow" :updatedInput (assoc (:input entry) :answers answers)})
          (let [text (history/answer-text (get-in entry [:ask :questions]) answers)]
            (when (seq text)
              (note-msg! id {:id (str "answer-" ask-id) :role "user" :text text :at (now)}))))
      "permit"
      (do (take-ask! id ask-id)
          ((:resolve entry) {:behavior "allow" :updatedInput (:input entry)}))
      nil)))

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
  "The turn's name in the picture history: the last sent message's uuid; a chat Claude works in
  before anything was sent here (after a runner start) gets one made."
  [id]
  (or (:turn-id (st id))
      (let [t (or (store/kv id "turn") (str (random-uuid)))]
        (swap-chat! id assoc :turn-id t)
        (store/kv! id "turn" t)
        t)))

(defn set-scene!
  "A chat's picture changed by the page (the editor, the form row): kept, saved (also as the picture
  of the turn that drew it, src/chat.ts userBlock), sent."
  [id s]
  (swap-chat! id assoc :scene s)
  (store/save-scene! id s)
  (when-let [last (peek (store/pictures id))]
    (store/save-picture! id (:turn last) s (:at last)))
  (emit! id {:type "scene" :scene s :changed [] :turn (turn-id! id)}))

(defn chat-folder [id] (or (:folder (store/chat id)) cwd))

(defn scene-of
  "A chat's current picture, or nil when there is no such chat."
  [id]
  (when (store/chat id) (current-scene id)))

(defn- draw [id input]
  (let [prev (current-scene id)
        {s :scene changed :changed error :error text :text} (codeforms/draw id prev input (chat-folder id))]
    (if error
      (text-result error)
      (let [turn (turn-id! id)]
        (swap-chat! id assoc :scene s)
        (store/save-scene! id s)
        (pictures/record! id turn prev s)
        (emit! id {:type "scene" :scene s :changed changed :turn turn})
        (emit! id {:type "pictures" :pictures (pictures/listing id)})
        ;; The draw tool's answer: what changed, and a screenshot of the picture as it looks now, so
        ;; Claude sees its own drawing; one that fails is said in the text, never the draw's failure.
        (-> (render/screenshot s (:color (store/chat id)))
            (.then (fn [{:keys [^js png error]}]
                     (if png
                       {:content [{:type "text" :text text}
                                  {:type "image" :data (.toString png "base64") :mimeType "image/png"}]}
                       (text-result (str text "\n\n(No screenshot of the picture: " error ")"))))))))))

(defn- sh-out [cmd args]
  (js/Promise. (fn [resolve]
                 (cp/execFile cmd (clj->js args) #js {:timeout 10000}
                              (fn [err stdout] (resolve (when-not err (str stdout))))))))

(defn- home-path [p] (str/replace (str p) #"^~(?=/|$)" (os/homedir)))

(defn- set-folder
  "set_folder (src/chat.ts folderTool): the folder the chat works in, its CLAUDE.md and git status."
  [id p]
  (let [abs (path/resolve cwd (home-path p))
        read #(-> (fsp/readFile (path/join abs %) "utf8") (.then (fn [t] (subs t 0 (min 6000 (count t))))) (.catch (fn [_] "")))]
    (if-not (try (.isDirectory (fs/statSync abs)) (catch :default _ false))
      (text-result (str "No folder at " abs ". Nothing changed."))
      (do (when (not= abs (:folder (store/chat id)))
            (store/update! id {:folder abs})
            (changes/folder-set! id abs (:running (st id)))
            (changed!))
          (-> (js/Promise.all #js [(read "CLAUDE.md") (read ".claude/CLAUDE.md") (sh-out "git" ["-C" abs "status" "--short" "--branch"])])
              (.then (fn [[md md2 status]]
                       (let [md (or (not-empty md) (not-empty md2))]
                         (text-result
                          (str/join "\n"
                                    [(str "Working folder is now " abs ". Run commands there (cd " abs " && …).")
                                     (if md
                                       (str "\n<claude-md path=\"" abs "/CLAUDE.md\">\n" md "\n</claude-md>")
                                       "\n(no CLAUDE.md in this folder)")
                                     (str "\n<git-status>\n"
                                          (if status (str/join "\n" (take 40 (.split status "\n"))) "(not a git repository)")
                                          "\n</git-status>")]))))))))))

(def ^:private image-types {".png" "image/png" ".jpg" "image/jpeg" ".jpeg" "image/jpeg" ".gif" "image/gif" ".webp" "image/webp"})
(def ^:private shown-max 5000000)

(defn- show-image
  "An image file into the chat (src/chat.ts showImage): the result carries it, so it is saved with
  the session (the page shows it under the step) and Claude sees it too."
  [id {p :path caption :caption}]
  (let [abs (path/resolve (or (:folder (store/chat id)) cwd) (home-path p))
        type (get image-types (str/lower-case (path/extname abs)))
        st (try (fs/statSync abs) (catch :default _ nil))]
    (cond (not type) (refusal (str abs " is not a png, jpeg, gif or webp picture."))
          (not (and st (.isFile st))) (refusal (str "No file at " abs "."))
          (> (.-size st) shown-max) (refusal (str abs " is " (js/Math.round (/ (.-size st) 1e6)) " MB; the chat shows pictures up to "
                                                  (/ shown-max 1e6) " MB. Make it smaller first."))
          :else {:content [{:type "image" :data (.toString (fs/readFileSync abs) "base64") :mimeType type}
                           {:type "text" :text (str "Shown in the chat: " (path/basename abs)
                                                    (when (seq caption) (str " (" caption ")")) ".")}]})))

(defn- mcp-call [id {:keys [server tool args]}]
  (case [server tool]
    ["picture" "draw"] (draw id args)
    ["picture" "templates"] (text-result (codeforms/template-list))
    ["picture" "save_template"] (text-result (codeforms/save-template! (current-scene id) args))
    ["workspace" "set_folder"] (set-folder id (:path args))
    ["workspace" "run_with_secret"] (secret-ask id args)
    ["workspace" "show_image"] (show-image id args)
    (throw (js/Error. (str "No tool " server "/" tool)))))

(defn- short-json [v n]
  (let [s (str (js/JSON.stringify (clj->js v)))] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn- can-use
  "Whether a tool may run (src/chat.ts ask). Every tool may (the user, 2026-10-22: \"give claude all
  permission\"); bypass mode still sends a few calls here (its own safety checks): they are allowed
  and logged. AskUserQuestion becomes a question ask, ExitPlanMode a permit ask (the plan to OK)."
  [id rpc {:keys [name input toolUseID title description]}]
  (let [ask-id (or toolUseID (str (random-uuid)))]
    (cond
      (and (= name "AskUserQuestion") (codeforms/off? id "questions"))
      {:behavior "deny" :message "The user switched questions off in Claudia for this chat. Don't ask with buttons: decide with your best judgment and say in one line what you chose and why."}
      (= name "AskUserQuestion")
      (js/Promise.
       (fn [resolve]
         (add-ask! id {:ask {:id ask-id :kind "question" :questions (:questions input)}
                       :input input :resolve resolve :rpc rpc})))
      (= name "ExitPlanMode")
      (js/Promise.
       (fn [resolve]
         (let [label (history/tool-label name)]
           (add-ask! id {:ask {:id ask-id :kind "permit" :tool label
                               :title (or (not-empty title) (str "Claude wants to use " label))
                               :description (or (not-empty description) (history/tool-title name input))
                               :detail (let [d (js/JSON.stringify (clj->js input) nil 2)]
                                         (if (> (count d) 2000) (str (subs d 0 1999) "…") d))}
                         :input input :resolve resolve :rpc rpc}))))
      :else
      (do (js/console.log (str "[" (subs id 0 8) "] auto-allowed " name ": " (short-json input 300)))
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

(defn- on-rpc [{:keys [rpc id kind args]}]
  (-> (js/Promise.resolve)
      (.then (fn [] (case kind
                      "mcp" (mcp-call id args)
                      "canUseTool" (can-use id rpc args)
                      "hook" (case (:event args)
                               "PreToolUse" (changes/before-edit! id (get-in args [:input :tool_input]))
                               "PostToolUse" (do (worktree/report! (store/chat id) (:input args))
                                                 (folder-from-edit! id (:input args))
                                                 {:continue true})
                               {:continue true})
                      {:continue true})))
      (.then (fn [v] (hostlink/send! {:op "reply" :rpc rpc :value v}))
             (fn [e] (hostlink/send! {:op "reply" :rpc rpc :error (message e)})))))

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
  (let [f (.-CLAUDIA_TOKEN_FILE env)
        t (when f (try (str/trim (fs/readFileSync f "utf8")) (catch :default _ nil)))]
    (if (seq t) t (throw (js/Error. "No Claude token yet: add one in Settings (bottom left).")))))

(defn- account-of
  "The account (token) name a session runs on (src/account.ts): a hash of the token, so replacing it
  restarts each chat's Claude Code on its next message."
  [t]
  (str "token-" (subs (.digest (.update (crypto/createHash "sha256") t) "hex") 0 8)))

;; claudia-progress (bin/) is on the PATH of every chat's commands.
(def ^:private bin-dir (path/resolve (if (exists? js/__dirname) js/__dirname (js/process.cwd)) ".." "bin"))

(defn- run-env
  "The environment chats run with: the runner's own, the token (read on every start, so a new token
  takes effect with the next session) and Claudia's helpers on the PATH."
  ([] (run-env (token)))
  ([t]
   (meta/use-token! t)
   (assoc (into {} (for [k (js-keys env)] [k (aget env k)]))
          "CLAUDE_CODE_OAUTH_TOKEN" t
          "PATH" (str bin-dir ":" (.-PATH env)))))

(defonce ^:private agent-timers (atom {}))

(defn- emit-agents!
  "The chat's agent list to the page (a little later, once for a burst of task events); the rows
  when its running count changed. The last agent ending with no turn running starts the settle."
  [id]
  (when-not (get @agent-timers id)
    (swap! agent-timers assoc id
           (js/setTimeout
            (fn []
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
            250))))

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
               (when (number? (:totalTokens c))
                 (let [ctx {:used (:totalTokens c) :max (:maxTokens c)
                            :percent (js/Math.round (or (:percentage c) 0)) :model (:model c)}]
                   (swap-chat! id assoc :context ctx)
                   (store/kv! id "context" ctx)
                   (emit! id {:type "context" :context ctx})))))
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

(defn- hash36 [s] (.toString (js/parseInt (subs (.digest (.update (crypto/createHash "sha256") (str s)) "hex") 0 12) 16) 36))

(defn- prompt-version [] (hash36 (codeforms/prompt-for)))
(defn- tools-version [id] (hash36 (js/JSON.stringify (clj->js (codeforms/mcp-for id)))))

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
    (let [mcp (codeforms/mcp-for id)
          v (tools-version id)]
      (-> (hostlink/control! id "setMcp" mcp)
          (.then (fn [_] (store/kv! id "session" (assoc (store/kv id "session") :tools v)))
                 (fn [e]
                   (js/console.error (str "[" (subs id 0 8) "] tools not swapped (" (message e) "); restarting after this turn"))
                   (swap-chat! id assoc :stale true)
                   (restart-if-stale! id)))))))

(defn tools-changed!
  "The chat's form row changed: its live session gets the new tools now."
  [id]
  (swap-tools! id))

(defn prefs-changed!
  "A form deleted or restored (the profile's prefs): every live chat gets the new draw tool now, and
  a new system prompt after its turn."
  []
  (doseq [[id s] @state :when (:live s)]
    (swap-tools! id)
    (when (not= (prompt-version) (:prompt (store/kv id "session")))
      (swap-chat! id assoc :stale true)
      (restart-if-stale! id))))

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
       (catch :default e (js/console.error "meta:" (message e))))
  ;; Someone switched the token in Settings: the new plan's limits show now (runner.ts watch).
  (when-let [f (not-empty (.-CLAUDIA_TOKEN_FILE env))]
    (try (fs/watch (path/dirname f)
                   (fn [_ file]
                     (when (= file (path/basename f))
                       (try (meta/use-token! (token)) (catch :default _ nil)))))
         (catch :default e (js/console.error "token watch:" (message e))))))

;; SDK messages -----------------------------------------------------------------------------------

(defn- take-in!
  "Claude Code took a message sent mid-turn in (started) or dropped it (cancelled)."
  [id uuid state]
  (when-let [m (get-in (st id) [:unsaved uuid])]
    (when (:queued m)
      (case state
        "started" (let [m (dissoc m :queued)]
                    (swap-chat! id (fn [s] (-> s
                                               (assoc-in [:unsaved uuid] m)
                                               (update :turn (fn [t] (conj (vec (remove #(= uuid (:id %)) t)) m))))))
                    (emit! id {:type "msg" :msg m}))
        "cancelled" (do (swap-chat! id (fn [s] (-> s
                                                   (update :unsaved dissoc uuid)
                                                   (update :turn (fn [t] (vec (remove #(= uuid (:id %)) t)))))))
                        (emit! id {:type "msg" :msg {:id uuid :role "user" :gone true}}))
        nil))))

(defn- turn-end! [id m]
  (let [ok (and (= "success" (:subtype m)) (not (:is_error m)))
        stopped (boolean (:stopping (st id)))
        error (when-not ok
                (if (= "success" (:subtype m))
                  (str (:result m))
                  (or (some->> (:errors m) seq (str/join "\n")) (:subtype m))))
        reply (if ok (plain-start (:result m) 200) "")]
    ;; How it ended, for the hub's pushes and badge: the result's uuid (one id per turn), when, how,
    ;; and the reply's start.
    (store/update! id (cond-> {:ended (now) :last_turn (or (:uuid m) (str (random-uuid)))
                               :stopped stopped :failed (boolean (and (not stopped) error)) :reply reply}
                        ok (assoc :preview reply)))
    (drop-asks! id true "The turn ended.")
    (swap-chat! id assoc :partial nil :stopping false :stream-counts {} :final-counts (atom {}))
    (when (and error (not stopped)) (emit! id {:type "error" :text error}))
    ;; A turn that ended took in everything sent during it (a send not taken in starts the next).
    (doseq [[uuid msg] (:unsaved (st id)) :when (:queued msg)] (take-in! id uuid "started"))
    (set-running! id false)
    (changed!)
    (read-context! id)
    (refresh-title! id)
    (-> (changes/finish! id)
        (.then (fn [rows] (when (seq rows) (emit! id {:type "changes" :changes rows})))))))

(defn- on-stream [id e]
  (case (:type e)
    "message_start" (swap-chat! id assoc :mid (get-in e [:message :id]))
    "content_block_start"
    (do
     (when (= "thinking" (get-in e [:content_block :type])) (emit! id {:type "status" :running true :note "Thinking"}))
     (when (= "text" (get-in e [:content_block :type]))
      (emit! id {:type "status" :running true})
      (let [mid (:mid (st id))
            n (get-in (swap-chat! id update-in [:stream-counts mid] (fnil inc -1)) [id :stream-counts mid])
            msg {:id (str mid ":" n) :role "assistant" :text "" :at (now)}]
        (swap-chat! id assoc :partial msg)
        (note-msg! id msg))))
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
                      (tasks/note-calls! id (:message m))
                      (doseq [msg (history/assistant-msgs (:message m) counts (now) folder)]
                        (note-msg! id msg))))
      "user" (when top
               ;; A background task's notice, when Claude Code hands it on as a user message.
               (let [c (get-in m [:message :content])
                     text (if (string? c) c (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) (when (sequential? c) c))))]
                 (when-let [notice (history/task-notice (history/strip-reminders text))]
                   (note-msg! id {:id (or (:uuid m) (str "notice-" (now))) :role "note" :text notice :at (now)}))
                 (doseq [b (when (sequential? c) c)
                         :when (= "tool_result" (:type b))
                         :let [msg (some #(when (= (:id %) (:tool_use_id b)) %) (:turn (st id)))]
                         :when msg]
                   (note-msg! id (cond-> (assoc msg :result {:error (boolean (:is_error b))
                                                             :text (history/cut-to (history/result-text (:content b)) 4000)})
                                   (:is_error b) (assoc :error true))))))
      "result" (turn-end! id m)
      "command_lifecycle" (take-in! id (:command_uuid m) (:state m))
      "system" (case (:subtype m)
                 "compact_boundary"
                 (do (note-msg! id {:id (:uuid m) :role "note" :at (now)
                                    :text (str "Context compacted ("
                                               (js/Math.round (/ (or (get-in m [:compact_metadata :pre_tokens]) 0) 1000))
                                               "k tokens before).")})
                     (read-context! id))
                 "init"
                 (do ;; A turn is starting (init comes ahead of its messages): a settling chat waits for it.
                     (when (:settling (st id)) (settle! id settle-init-ms))
                     (swap-chat! id assoc :model (:model m) :commands (vec (:slash_commands m)))
                     (emit! id {:type "settings" :settings (settings id) :model (:model m)
                                :commands (vec (:slash_commands m))})
                     (read-context! id))
                 "api_retry" (emit! id {:type "status" :running true :note "Retrying…"})
                 "status" (when (= "compacting" (:status m)) (emit! id {:type "status" :running true :note "Compacting"}))
                 ;; A slash command's output, and Claude Code's warnings and notices.
                 "local_command_output"
                 (note-msg! id {:id (or (:uuid m) (str "out-" (now))) :role "note" :text (str (:content m)) :at (now)})
                 "informational"
                 (when (#{"warning" "notice"} (:level m))
                   (note-msg! id {:id (or (:uuid m) (str "info-" (now))) :role "note" :text (str (:content m)) :at (now)}))
                 (do (tasks/load! id true)
                     (tasks/on-system! id m)
                     ;; A background task ended: its notice, as the session file keeps it.
                     (when (and (= "task_notification" (:subtype m)) (not (:ambient m)) (not (:skip_transcript m))
                                (seq (:summary m))
                                ;; An agent's own shells and agents are part of it: their notices are its.
                                (not (tasks/child? id (:task_id m)))
                                ;; A foreground agent's answer is its tool result, not a notice.
                                (tasks/background? id (:task_id m)))
                       (note-msg! id {:id (str "task-" (:task_id m) "-" (now)) :role "note" :text (:summary m) :at (now)}))))
      "rate_limit_event" (try (meta/note-limits! (token) (:rate_limit_info m))
                              (catch :default _ nil))
      nil)))

(defn- ended! [id error]
  (swap-chat! id assoc :live false :partial nil)
  (tasks/session-gone! id)
  (drop-asks! id false nil)
  (when error (emit! id {:type "error" :text error}))
  (set-running! id false))

(defn on-host-event [{:keys [ev id] :as e}]
  (case ev
    "sessions" (let [live (into {} (map (juxt :id :busy)) (:list e))]
                 ;; Sessions the host no longer holds (it restarted) have ended.
                 (doseq [[cid s] @state :when (and (:live s) (not (contains? live cid)))]
                   (ended! cid "The session host restarted; this chat's work stopped. Send a message to carry on."))
                 (doseq [[cid busy] live
                         :let [was (:live (st cid))
                               kept (store/kv cid "session")]]
                   (tasks/load! cid true)
                   (swap-chat! cid assoc :live true :account (:account kept))
                   (when busy (swap-chat! cid assoc :since (or (:since (st cid)) (now))))
                   (set-running! cid (boolean busy))
                   ;; After a runner start: the runner's tools or instructions changed since this
                   ;; session started (an update): the new tools now, the instructions after its turn.
                   (when-not was
                     (when (not= (:tools kept) (tools-version cid)) (swap-tools! cid))
                     (when (not= (:prompt kept) (prompt-version))
                       (swap-chat! cid assoc :stale true)
                       (restart-if-stale! cid)))))
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

(defn- start-spec [id resume? env]
  {:options (cond-> {:cwd cwd
                     :env env
                     (if resume? :resume :sessionId) id
                     :systemPrompt {:type "preset" :preset "claude_code" :append (codeforms/prompt-for)}
                     :allowedTools tools/allowed-tools
                     ;; Same as the user's terminal Claude (defaultMode bypassPermissions).
                     :permissionMode "bypassPermissions"
                     :allowDangerouslySkipPermissions true
                     :includePartialMessages true}
              (:model (settings id)) (assoc :model (:model (settings id)))
              (:effort (settings id)) (assoc :effort (:effort (settings id))))
   :mcp (codeforms/mcp-for id)
   ;; A copy of each file before Claude's edit tools first touch it (the turn's changes).
   :hooks {:PreToolUse [{:matcher changes/edit-tools}]
           ;; After an edit: a chat with no folder gets the edited file's project. Edits and commands
           ;; inside Eye on Rainbow's worktrees are reported (where configured).
           :PostToolUse [{:matcher (if (worktree/on?) (str changes/edit-tools "|Bash") changes/edit-tools)}]}})

(defn- ensure-session!
  "The chat's Claude Code, started (or resumed) on the token in use. The token was replaced since its
  session started: it starts again on the new one (not while work it started still runs, which that
  would end)."
  [id]
  (let [t (token)
        acct (account-of t)]
    (when (and (:live (st id)) (not (:running (st id))) (zero? (tasks/working id))
               (some? (:account (st id))) (not= acct (:account (st id))))
      (close-session! id))
    (if (:live (st id))
      (js/Promise.resolve nil)
      (-> (sdk/getSessionInfo id #js {:dir cwd})
          (.catch (fn [_] nil))
          (.then (fn [info]
                   (hostlink/send! {:op "start" :id id :spec (start-spec id (some? info) (run-env t))})
                   (codeforms/forms-seen-now! id)
                   (store/kv! id "session" {:account acct :tools (tools-version id) :prompt (prompt-version)})
                   (swap-chat! id assoc :live true :account acct :stale false :final-counts (atom {}))))))))

(defn send-text!
  "A message into the chat; images: [{media_type data}] sent before the text (photos, video frames).
  Sent while a turn runs, it is queued until Claude takes it in."
  [id text & [images]]
  (-> (js/Promise.resolve)
      (.then (fn []
               (let [live? (boolean (:live (st id)))]
                 (.then (ensure-session! id) (fn [] live?)))))
      (.then (fn [was-live?]
               (let [uuid (str (random-uuid))
                     queued (boolean (:running (st id)))
                     msg (cond-> {:id uuid :role "user" :text text :at (now)}
                           (seq images) (assoc :images (count images))
                           queued (assoc :queued true))]
                 (when-not queued (swap-chat! id assoc :turn []))
                 (swap-chat! id assoc :turn-id uuid)
                 (store/kv! id "turn" uuid)
                 (swap-chat! id assoc-in [:unsaved uuid] msg)
                 (note-msg! id msg)
                 ;; A changed form row and the forms news go with this message, as a reminder the chat
                 ;; doesn't show (the news only to a session that ran with the old tools).
                 (hostlink/send! (cond-> {:op "send" :id id :text (codeforms/take-note! id text was-live?) :uuid uuid}
                                   (seq images) (assoc :images images)))
                 (set-running! id true))))
      (.catch (fn [e] (emit! id {:type "error" :text (message e)})))))

(defn new-chat!
  "Starts a chat with its first message; returns its id (the Claude Code session id)."
  [text folder & [images]]
  (let [id (str (random-uuid))]
    (store/add! {:id id :title (first-sent-title text) :at (now)
                 :folder (when (seq folder) folder)
                 :color (least-used-colour)})
    (changed!)
    (send-text! id text images)
    id))

(defn stop! [id]
  (drop-asks! id true "The user stopped the turn.")
  (when (:running (st id))
    (swap-chat! id assoc :stopping true))
  (hostlink/send! {:op "interrupt" :id id}))

(defn snapshot
  "Promise of the chat frame: the session file's history with this turn's live Msgs and the sent
  messages the file doesn't hold yet laid over it."
  [id]
  (let [r (store/chat id)]
    (-> (history/load cwd id (:folder r))
        (.catch (fn [e] (js/console.error "history:" (message e)) []))
        (.then (fn [hist]
                 (let [known (set (map :id hist))
                       ;; Sent messages the session file has now are no longer kept aside.
                       _ (swap-chat! id update :unsaved #(into {} (remove (fn [[k]] (known k))) %))
                       s (st id)
                       turn (:turn s)
                       partial (:partial s)
                       by-id (into {} (map (juxt :id identity)) turn)
                       notes (set (keep #(when (= "note" (:role %)) (:text %)) hist))
                       in-turn (set (map :id turn))
                       msgs (-> (mapv #(get by-id (:id %) %) hist)
                                (into (remove #(or (known (:id %)) (and (= "note" (:role %)) (notes (:text %)))) turn))
                                (into (remove #(or (known (:id %)) (in-turn (:id %))) (vals (:unsaved s)))))]
                   (tasks/load! id (boolean (:live s)))
                   {:type "chat" :chat id :title (:title r) :folder (:folder r)
                    :agents (tasks/agents cwd id (:folder r))
                    :changes (store/changes id)
                    :settings (settings id)
                    :model (:model s)
                    :commands (:commands s)
                    :context (or (:context s) (store/kv id "context"))
                    :messages (if partial (mapv #(if (= (:id %) (:id partial)) partial %) msgs) msgs)
                    :scene (current-scene id)
                    :running (boolean (:running s))
                    :since (:since s)
                    :asks (vec (map :ask (vals (:asks s))))
                    :pictureSettings (codeforms/chat-settings id)}))))))
