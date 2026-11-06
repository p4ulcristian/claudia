(ns claudia.slave.history
  "A chat's messages as the page shows them (Msg in PROTOCOL.md), from Claude Code's own session file
  and from live SDK messages alike: user text, assistant text, tool steps as one-line summaries.

  Ids are the same both ways, so a live message replaces itself when the chat is opened again: user
  messages keep the uuid sent with them, a tool step is its tool_use id, and assistant text is
  <API message id>:<n>, n counting the text blocks of that API message (Claude Code writes one entry
  per content block, the stream numbers them across the message)."
  (:require ["@anthropic-ai/claude-agent-sdk" :as sdk]
            ["node:fs/promises" :as fsp]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.node.env :as env]
            [claudia.shared.text :as text]))

(defn- short-text
  "s on one line, in at most n characters."
  [s n]
  (text/fit (text/one-line s) n))

(defn shown-path
  "A path as the user reads it: relative to the working folder when inside it, else with ~."
  [p folder]
  (let [p (str p)
        home (os/homedir)]
    (cond (and folder (str/starts-with? p (str folder "/"))) (subs p (inc (count folder)))
          (str/starts-with? p (str home "/")) (str "~" (subs p (count home)))
          :else p)))

(defn tool-summary
  "One line per tool call: `Edit src/x.cljs`, `$ npm test`, `draw`."
  [tool input folder]
  (let [i (or input {})
        p #(shown-path % folder)]
    (case tool
      "Bash" (str "$ " (short-text (first (str/split-lines (str (:command i)))) 90))
      ("Read" "Write" "Edit") (str tool " " (p (:file_path i)))
      "NotebookEdit" (str tool " " (p (:notebook_path i)))
      ("Grep" "Glob") (str tool " " (short-text (:pattern i) 80))
      "WebFetch" (str "fetch " (short-text (:url i) 80))
      "WebSearch" (str "search " (short-text (:query i) 80))
      ("Agent" "Task") (str "agent: " (short-text (:description i) 80))
      "TodoWrite" (str (count (:todos i)) " todos")
      "AskUserQuestion" (str "ask: " (str/join ", " (map :header (:questions i))))
      "mcp__picture__draw" (if (seq (:title i)) (str "draw: " (short-text (:title i) 60)) "draw")
      "mcp__workspace__set_folder" (str "folder " (shown-path (str (:path i)) nil))
      "mcp__workspace__run_with_secret" (str "secret: " (short-text (:reason i) 80))
      "mcp__workspace__show_image" (str "image " (if (seq (:caption i)) (short-text (:caption i) 60) (p (:path i))))
      "mcp__workspace__show_video" (str "video " (if (seq (:caption i)) (short-text (:caption i) 60) (p (:path i))))
      (if (str/starts-with? tool "mcp__")
        (str/join " " (rest (str/split tool #"__")))
        tool))))

;; The TypeScript Claudia's one-line step (src/chat.ts toolLabel, toolTitle): sent with each tool Msg
;; as label and title beside the summary, with the call's input (detail) and its result.

(defn tool-label [tool]
  (case tool
    "mcp__picture__draw" "Draw"
    "mcp__workspace__set_folder" "Folder"
    "AskUserQuestion" "Ask"
    (if (str/starts-with? (str tool) "mcp__") (str/join " " (rest (str/split tool #"__"))) tool)))

(defn tool-title [tool input]
  (let [i (or input {})
        base #(last (str/split (str %) #"/"))]
    (case tool
      "Bash" (text/fit (or (not-empty (:description i)) (:command i) "") 90)
      ("Read" "Write" "Edit" "NotebookEdit") (base (or (:file_path i) (:notebook_path i)))
      ("Grep" "Glob") (text/fit (or (:pattern i) "") 80)
      "WebFetch" (text/fit (or (:url i) "") 80)
      "WebSearch" (text/fit (or (:query i) "") 80)
      ("Agent" "Task") (text/fit (or (:description i) "") 80)
      "TodoWrite" (str (count (:todos i)) " todos")
      "mcp__picture__draw" (if (seq (:title i)) (str "picture: " (:title i)) "picture")
      "mcp__workspace__set_folder" (str/replace (str (:path i)) (os/homedir) "~")
      "AskUserQuestion" (str/join ", " (map :header (:questions i)))
      "")))

(defn strip-reminders [s]
  (str/trim (str/replace (str s) #"<system-reminder>[\s\S]*?</system-reminder>" "")))

(defn task-notice
  "Claude Code stores \"background task finished\" notices as user messages: their summary, or nil."
  [text]
  (when (str/starts-with? text "<task-notification>")
    (or (some-> (re-find #"<summary>([\s\S]*?)</summary>" text) second str/trim)
        "A background task finished.")))

(defn command-tags
  "The tags Claude Code keeps a slash command in, read from user text: {:name :args :out} (each nil
  when missing; out is <local-command-stdout> or -stderr) and :message? (it has <command-message>)."
  [text]
  {:name (some-> (re-find #"<command-name>([\s\S]*?)</command-name>" text) second str/trim)
   :args (some-> (re-find #"<command-args>([\s\S]*?)</command-args>" text) second str/trim)
   :out (some-> (re-find #"<local-command-std(?:out|err)>([\s\S]*?)</local-command-std(?:out|err)>" text) second str/trim)
   :message? (boolean (re-find #"<command-message>" text))})

(defn typed-command
  "A slash command as the user typed it: \"/name args\"."
  [nm args]
  (str (if (str/starts-with? nm "/") nm (str "/" nm)) (when (seq args) (str " " args))))

(defn command-msg
  "Claude Code keeps a slash command as tagged user text (src/chat.ts commandParts): the command
  (<command-name> and <command-args>, shown as the user's own \"/name args\") and its output
  (<local-command-stdout> or -stderr, a note); a caveat is dropped. [{:role :text :out?}] (empty:
  nothing to show), or nil for any other text."
  [text]
  (if (str/starts-with? text "<local-command-caveat>")
    []
    (let [{nm :name args :args out :out message? :message?} (command-tags text)]
      (when (or (seq nm) (some? out) message?)
        (cond-> []
          (seq nm) (conj {:role "user" :text (typed-command nm args)})
          (seq out) (conj {:role "note" :text out :out true}))))))

(defn result-text [content]
  (cond (string? content) content
        (sequential? content) (str/join "\n" (for [b content]
                                               (case (:type b) "text" (:text b) "image" "[image]" "")))
        :else ""))

(defn answer-text
  "An AskUserQuestion answer as the user's own message."
  [questions answers]
  (let [pairs (for [q questions
                    :let [a (get answers (keyword (:question q)))]
                    :when (seq a)]
                [(or (not-empty (:header q)) (:question q)) a])]
    (if (= 1 (count pairs))
      (second (first pairs))
      (str/join "\n" (for [[h a] pairs] (str "**" h ":** " a))))))

(defn answers-in
  "An AskUserQuestion result's answers ({question answer}): the result reads ... \"question\"=\"answer\" ..."
  [result]
  (into {} (for [[_ q a] (re-seq #"\"((?:[^\"\\\\]|\\\\.)*)\"=\"((?:[^\"\\\\]|\\\\.)*)\"" (str result))]
             [(keyword q) a])))

(defn- tool-msg
  "A tool_use block as its step Msg."
  [b at folder]
  (cond-> {:id (:id b) :role "tool" :tool (:name b)
           :summary (tool-summary (:name b) (:input b) folder) :at at
           :label (tool-label (:name b)) :title (tool-title (:name b) (:input b))
           :detail (text/fit (js/JSON.stringify (clj->js (:input b)) nil 2) 4000)}
    ;; the page shows the image from GET /api/chats/:id/shown/<this id>
    (= "mcp__workspace__show_image" (:name b)) (assoc :image true)
    ;; …and the video from the same address
    (= "mcp__workspace__show_video" (:name b)) (assoc :video true)))

(defn read-assistant
  "One assistant API message's content blocks -> [Msgs counts]. counts: API message id -> text blocks
  seen so far (carried across the entries of one message); every text block counts, blank or not."
  [message counts at folder]
  (let [mid (:id message)]
    (reduce (fn [[msgs cs] b]
              (case (:type b)
                "text" (let [cs (update cs mid (fnil inc -1))]
                         [(cond-> msgs
                            (seq (str/trim (str (:text b))))
                            (conj {:id (str mid ":" (get cs mid)) :role "assistant" :text (:text b) :at at}))
                          cs])
                "tool_use" [(conj msgs (tool-msg b at folder)) cs]
                [msgs cs]))
            [[] counts]
            (:content message))))

(defn projects-root
  "Claude Code's projects folder: CLAUDE_CONFIG_DIR's, else ~/.claude's."
  []
  (path/join (env/env "CLAUDE_CONFIG_DIR" (path/join (os/homedir) ".claude")) "projects"))

(defn projects-dir
  "The folder holding the session files of chats working in cwd."
  [cwd]
  (path/join (projects-root) (str/replace cwd #"[^a-zA-Z0-9]" "-")))

(defn session-file [cwd id]
  (path/join (projects-dir cwd) (str id ".jsonl")))

(defn jsonl-entries
  "A session file's text as its entries (JS objects); blank and broken lines are skipped."
  [text]
  (vec (keep #(when-not (str/blank? %) (try (js/JSON.parse %) (catch :default _ nil)))
             (str/split-lines (str text)))))

(defn- file-entries
  "The session file's entries as JS objects (none when there is no file yet)."
  [cwd id]
  (-> (fsp/readFile (session-file cwd id) "utf8")
      (.then jsonl-entries)
      (.catch (fn [_] []))))

(defn sent-mid-turn
  "A message sent while a turn ran isn't saved as a user entry: Claude Code saves a queued_command
  attachment holding the sent uuid as source_uuid (src/chat.ts sentMidTurn). The sent uuid, or nil."
  [^js e]
  (let [^js a (when (and e (= "attachment" (.-type e))) (.-attachment e))]
    (when (and a (= "queued_command" (.-type a)) (not (.-isMeta a)) (not (.-forwardedIntent a))
               (or (string? (.-prompt a)) (array? (.-prompt a))))
      (if (and (string? (.-source_uuid a)) (seq (.-source_uuid a))) (.-source_uuid a) (.-uuid e)))))

;; Earlier chains ---------------------------------------------------------------------------------

(defn- jget [o k] (when o (unchecked-get o k)))

(defn- index-by-uuid [entries]
  (into {} (keep (fn [e] (when (jget e "uuid") [(jget e "uuid") e]))) entries))

(defn- chain-start
  "The first entry of the chain the entry with this uuid is on (following parentUuid)."
  [index entry-uuid]
  (loop [e (get index entry-uuid) n 0]
    (if (and e (jget e "parentUuid") (< n 1000000))
      (recur (get index (jget e "parentUuid")) (inc n))
      e)))

(defn- compacted-note
  "A compact_boundary entry as the message the chat shows in place of its summary."
  [boundary]
  {:type "compacted" :uuid (jget boundary "uuid") :parent_tool_use_id nil
   :compacted (or (jget (jget boundary "compactMetadata") "preTokens") 0)})

(defn- chain-msg
  "An earlier chain's entry as an SDK message (a message sent mid-turn as the user's), or nil when
  the chat does not show it."
  [e]
  (cond
    (and (#{"user" "assistant"} (jget e "type")) (not (jget e "isSidechain")) (not (jget e "isMeta"))
         (not (jget e "isCompactSummary")) (jget e "message"))
    {:type (jget e "type") :uuid (jget e "uuid") :parent_tool_use_id nil
     :message (js->clj (jget e "message") :keywordize-keys true)}
    (and (not (jget e "isSidechain")) (sent-mid-turn e))
    {:type "user" :uuid (sent-mid-turn e) :parent_tool_use_id nil
     :message {:role "user" :content (js->clj (jget (jget e "attachment") "prompt") :keywordize-keys true)}}
    :else nil))

(defn- earlier-chain
  "The chain ending at entry e, walked back by parentUuid: [its messages, oldest first; its first entry]."
  [index e]
  (loop [e e start nil acc ()]
    (if-not e
      [acc start]
      (recur (when (jget e "parentUuid") (get index (jget e "parentUuid")))
             e
             (if-let [m (chain-msg e)] (cons m acc) acc)))))

(defn- earlier-boundary?
  "Whether the chain starts at a compaction not followed yet (so a chain lies before it)."
  [boundary seen]
  (and boundary (= "compact_boundary" (jget boundary "subtype")) (jget boundary "logicalParentUuid")
       (not (seen (jget boundary "uuid")))))

(defn with-earlier-chains
  "A compaction starts a new chain in the session file and the SDK returns only the last one (src/chat.ts
  withEarlierChains): the chains before it are read from the file, oldest first, so the whole chat
  shows; each compaction becomes {:type \"compacted\" :uuid :compacted pre-tokens} in place of its
  summary message. msgs: the SDK's messages (data); entries: the file's (JS)."
  [msgs entries]
  (if (or (empty? msgs) (not (some #(= "compact_boundary" (jget % "subtype")) entries)))
    msgs
    (let [index (index-by-uuid entries)
          summaries (set (keep (fn [e] (when (jget e "isCompactSummary") (jget e "uuid"))) entries))]
      (loop [out (vec (remove #(summaries (:uuid %)) msgs))
             boundary (chain-start index (:uuid (first msgs)))
             seen #{}]
        (if (earlier-boundary? boundary seen)
          (let [[chain start] (earlier-chain index (get index (jget boundary "logicalParentUuid")))]
            (recur (into (conj (vec chain) (compacted-note boundary)) out) start (conj seen (jget boundary "uuid"))))
          out)))))

;; Loading ----------------------------------------------------------------------------------------

(defn- entry-times
  "uuid -> the entry's time (ms), for the entries that have both."
  [entries]
  (into {} (keep (fn [^js e] (when (and (.-uuid e) (.-timestamp e)) [(.-uuid e) (js/Date.parse (.-timestamp e))])))
        entries))

(defn- compacted-msg [m at]
  {:id (:uuid m) :role "note" :at at
   :text (str "Context compacted (" (js/Math.round (/ (:compacted m) 1000)) "k tokens before).")})

(defn- add-assistant
  "An assistant message's Msgs added; its tool steps' places (and an AskUserQuestion's questions) kept."
  [acc m at folder]
  (let [content (get-in m [:message :content])
        [msgs counts] (read-assistant (:message m) (:counts acc) at folder)]
    (reduce (fn [a msg]
              (cond-> a
                (= "tool" (:role msg)) (assoc-in [:tools (:id msg)] (count (:out a)))
                (= "AskUserQuestion" (:tool msg))
                (assoc-in [:asked (:id msg)] (some #(when (= (:id %) (:id msg)) (get-in % [:input :questions])) content))
                true (update :out conj msg)))
            (assoc acc :counts counts)
            msgs)))

(defn- text-user-msgs
  "A user entry whose content is text: a task notice, a slash command, or what the user wrote."
  [m content at]
  (let [text (strip-reminders content)
        notice (task-notice text)
        cmd (when-not notice (command-msg text))]
    (cond
      notice [{:id (:uuid m) :role "note" :text notice :at at}]
      cmd (mapv #(-> % (dissoc :out) (assoc :id (if (:out %) (str (:uuid m) ":out") (:uuid m)) :at at)) cmd)
      (seq text) [{:id (:uuid m) :role "user" :text text :at at}]
      :else [])))

(defn- add-tool-result
  "A tool_result block: its step gets the result; an AskUserQuestion's answer becomes the user's message."
  [acc b at]
  (let [i (get (:tools acc) (:tool_use_id b))
        questions (and (not (:is_error b)) (get (:asked acc) (:tool_use_id b)))
        answer (when questions (answer-text questions (answers-in (result-text (:content b)))))]
    (cond-> acc
      i (update-in [:out i] assoc :result {:error (boolean (:is_error b))
                                           :text (text/fit (result-text (:content b)) 4000)})
      (and i (:is_error b)) (assoc-in [:out i :error] true)
      (seq answer) (update :out conj {:id (str "answer-" (:tool_use_id b)) :role "user" :text answer :at at}))))

(defn- blocks-user-msg
  "A user entry's text and image blocks as one Msg (a task notice as a note), or nil."
  [m content at]
  (let [text (str/join "\n" (filter seq (keep #(when (= "text" (:type %)) (strip-reminders (:text %))) content)))
        images (count (filter #(= "image" (:type %)) content))
        notice (task-notice text)]
    (cond
      notice {:id (:uuid m) :role "note" :text notice :at at}
      (or (seq text) (pos? images)) (cond-> {:id (:uuid m) :role "user" :text text :at at}
                                      ;; GET /api/chats/:id/sent/<this id>/<n> for each
                                      (pos? images) (assoc :images images)))))

(defn- add-user-blocks [acc m content at]
  (let [acc (reduce #(if (= "tool_result" (:type %2)) (add-tool-result %1 %2 at) %1) acc content)
        msg (blocks-user-msg m content at)]
    (cond-> acc msg (update :out conj msg))))

(defn- add-msg [folder times acc m]
  (let [at (get times (:uuid m) 0)
        content (get-in m [:message :content])]
    (case (:type m)
      "compacted" (update acc :out conj (compacted-msg m at))
      "assistant" (add-assistant acc m at folder)
      "user" (if (string? content)
               (update acc :out into (text-user-msgs m content at))
               (add-user-blocks acc m content at))
      acc)))

(defn session-msgs
  "The chat's Msgs, oldest first, from the SDK's messages (data) and the session file's entries (JS)."
  [msgs entries folder]
  (:out (reduce (partial add-msg folder (entry-times entries))
                {:out [] :tools {} :asked {} :counts {}}
                (filter #(nil? (:parent_tool_use_id %)) (with-earlier-chains msgs entries)))))

(defn load
  "Promise of the chat's Msgs, oldest first."
  [cwd id folder]
  (-> (js/Promise.all #js [(sdk/getSessionMessages id #js {:dir cwd}) (file-entries cwd id)])
      (.then (fn [[msgs entries]] (session-msgs (js->clj msgs :keywordize-keys true) entries folder)))))
