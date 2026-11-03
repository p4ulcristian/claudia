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
            [clojure.string :as str]))

(defn- short-text [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

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
      (if (str/starts-with? tool "mcp__")
        (str/join " " (rest (str/split tool #"__")))
        tool))))

;; The TypeScript Claudia's one-line step (src/chat.ts toolLabel, toolTitle): sent with each tool Msg
;; as label and title beside beta's summary, with the call's input (detail) and its result.

(defn cut-to
  "s cut to n characters with an ellipsis (newlines kept)."
  [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

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
      "Bash" (cut-to (or (not-empty (:description i)) (:command i) "") 90)
      ("Read" "Write" "Edit" "NotebookEdit") (base (or (:file_path i) (:notebook_path i)))
      ("Grep" "Glob") (cut-to (or (:pattern i) "") 80)
      "WebFetch" (cut-to (or (:url i) "") 80)
      "WebSearch" (cut-to (or (:query i) "") 80)
      ("Agent" "Task") (cut-to (or (:description i) "") 80)
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

(defn command-msg
  "Claude Code keeps a slash command as user messages: the command (<command-name>, shown as the
  user's own \"/name args\"), its output (<local-command-stdout>, a note) and a caveat (dropped).
  {:role :text}, :drop, or nil for any other text."
  [text]
  (cond
    (str/starts-with? text "<local-command-caveat>") :drop
    (str/includes? text "<local-command-stdout>")
    (let [out (str/trim (str (second (re-find #"<local-command-stdout>([\s\S]*?)</local-command-stdout>" text))))]
      (if (seq out) {:role "note" :text out} :drop))
    (str/includes? text "<command-name>")
    (let [cmd (str/trim (str (second (re-find #"<command-name>([\s\S]*?)</command-name>" text))))
          args (str/trim (str (second (re-find #"<command-args>([\s\S]*?)</command-args>" text))))]
      {:role "user" :text (str/trim (str (if (str/starts-with? cmd "/") cmd (str "/" cmd)) " " args))})
    :else nil))

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

(defn assistant-msgs
  "One assistant API message's content blocks -> Msgs. counts: atom of API message id -> text blocks
  seen so far (shared across the entries of one message)."
  [message counts at folder]
  (let [mid (:id message)]
    (into []
          (keep (fn [b]
                  (case (:type b)
                    "text" (let [n (get (swap! counts update mid (fnil inc -1)) mid)]
                             (when (seq (str/trim (str (:text b))))
                               {:id (str mid ":" n) :role "assistant" :text (:text b) :at at}))
                    "tool_use" (cond-> {:id (:id b) :role "tool" :tool (:name b)
                                        :summary (tool-summary (:name b) (:input b) folder) :at at
                                        :label (tool-label (:name b)) :title (tool-title (:name b) (:input b))
                                        :detail (cut-to (js/JSON.stringify (clj->js (:input b)) nil 2) 4000)}
                                 ;; the page shows the image from GET /api/chats/:id/shown/<this id>
                                 (= "mcp__workspace__show_image" (:name b)) (assoc :image true))
                    nil)))
          (:content message))))

(defn session-file [cwd id]
  (path/join (or (.. js/process -env -CLAUDE_CONFIG_DIR) (path/join (os/homedir) ".claude"))
             "projects" (str/replace cwd #"[^a-zA-Z0-9]" "-") (str id ".jsonl")))

(defn- file-entries
  "The session file's entries as JS objects (none when there is no file yet)."
  [cwd id]
  (-> (fsp/readFile (session-file cwd id) "utf8")
      (.then (fn [text] (vec (keep #(when (seq %) (try (js/JSON.parse %) (catch :default _ nil))) (str/split-lines text)))))
      (.catch (fn [_] []))))

(defn- sent-mid-turn
  "A message sent while a turn ran is saved as a queued_command attachment holding the sent uuid as
  source_uuid (src/chat.ts sentMidTurn): that uuid, or nil."
  [^js e]
  (let [^js a (when (and e (= "attachment" (.-type e))) (.-attachment e))]
    (when (and a (= "queued_command" (.-type a)) (not (.-isMeta a)) (not (.-forwardedIntent a))
               (or (string? (.-prompt a)) (array? (.-prompt a))))
      (if (and (string? (.-source_uuid a)) (seq (.-source_uuid a))) (.-source_uuid a) (.-uuid e)))))

(defn- jget [o k] (when o (unchecked-get o k)))

(defn with-earlier-chains
  "A compaction starts a new chain in the session file and the SDK returns only the last one (src/chat.ts
  withEarlierChains): the chains before it are read from the file, oldest first, so the whole chat
  shows; each compaction becomes {:type \"compacted\" :uuid :compacted pre-tokens} in place of its
  summary message. msgs: the SDK's messages (data); entries: the file's (JS)."
  [msgs entries]
  (if (or (empty? msgs) (not (some #(= "compact_boundary" (jget % "subtype")) entries)))
    msgs
    (let [by-uuid (into {} (keep (fn [e] (when (jget e "uuid") [(jget e "uuid") e]))) entries)
          root-of (fn [uuid] (loop [e (get by-uuid uuid) n 0]
                               (if (and e (jget e "parentUuid") (< n 1000000))
                                 (recur (get by-uuid (jget e "parentUuid")) (inc n))
                                 e)))
          summaries (set (keep (fn [e] (when (jget e "isCompactSummary") (jget e "uuid"))) entries))
          note (fn [b] {:type "compacted" :uuid (jget b "uuid") :parent_tool_use_id nil
                            :compacted (or (jget (jget b "compactMetadata") "preTokens") 0)})
          as-msg (fn [e] {:type (jget e "type") :uuid (jget e "uuid") :parent_tool_use_id nil
                              :message (js->clj (jget e "message") :keywordize-keys true)})]
      (loop [out (vec (remove #(summaries (:uuid %)) msgs))
             boundary (root-of (:uuid (first msgs)))
             seen #{}]
        (if (and boundary (= "compact_boundary" (jget boundary "subtype")) (jget boundary "logicalParentUuid")
                 (not (seen (jget boundary "uuid"))))
          (let [[chain root] (loop [e (get by-uuid (jget boundary "logicalParentUuid")) root nil acc ()]
                               (if-not e
                                 [acc root]
                                 (recur (when (jget e "parentUuid") (get by-uuid (jget e "parentUuid")))
                                        e
                                        (cond
                                          (and (#{"user" "assistant"} (jget e "type")) (not (jget e "isSidechain")) (not (jget e "isMeta"))
                                               (not (jget e "isCompactSummary")) (jget e "message"))
                                          (cons (as-msg e) acc)
                                          (and (not (jget e "isSidechain")) (sent-mid-turn e))
                                          (cons {:type "user" :uuid (sent-mid-turn e) :parent_tool_use_id nil
                                                 :message {:role "user" :content (js->clj (jget (jget e "attachment") "prompt") :keywordize-keys true)}}
                                                acc)
                                          :else acc))))]
            (recur (into (into (vec chain) [(note boundary)]) out) root (conj seen (jget boundary "uuid"))))
          out)))))

(defn load
  "Promise of the chat's Msgs, oldest first."
  [cwd id folder]
  (-> (js/Promise.all #js [(sdk/getSessionMessages id #js {:dir cwd}) (file-entries cwd id)])
      (.then
       (fn [[msgs entries]]
         (let [times (into {} (keep (fn [^js e] (when (and (.-uuid e) (.-timestamp e)) [(.-uuid e) (js/Date.parse (.-timestamp e))]))) entries)
               counts (atom {})
               out (atom [])
               tools (atom {})   ; tool_use id -> index in out
               asked (atom {})]  ; AskUserQuestion tool_use id -> questions
           (doseq [m (with-earlier-chains (js->clj msgs :keywordize-keys true) entries)
                   :when (nil? (:parent_tool_use_id m))
                   :let [at (get times (:uuid m) 0)
                         message (:message m)
                         content (:content message)]]
             (case (:type m)
               "compacted"
               (swap! out conj {:id (:uuid m) :role "note" :at at
                                :text (str "Context compacted (" (js/Math.round (/ (:compacted m) 1000)) "k tokens before).")})
               "assistant"
               (doseq [msg (assistant-msgs message counts at folder)]
                 (when (= "tool" (:role msg))
                   (swap! tools assoc (:id msg) (count @out))
                   (when (= "AskUserQuestion" (:tool msg))
                     (swap! asked assoc (:id msg)
                            (some #(when (= (:id %) (:id msg)) (get-in % [:input :questions])) content))))
                 (swap! out conj msg))
               "user"
               (if (string? content)
                 (let [text (strip-reminders content)]
                   (let [cmd (command-msg text)]
                     (cond
                       (= :drop cmd) nil
                       cmd (swap! out conj (assoc cmd :id (:uuid m) :at at))
                       :else (if-let [notice (task-notice text)]
                               (swap! out conj {:id (:uuid m) :role "note" :text notice :at at})
                               (when (seq text) (swap! out conj {:id (:uuid m) :role "user" :text text :at at}))))))
                 (let [texts (atom [])]
                   (doseq [b content]
                     (case (:type b)
                       "tool_result"
                       (do (when-let [i (get @tools (:tool_use_id b))]
                             (swap! out update i assoc :result {:error (boolean (:is_error b))
                                                                :text (cut-to (result-text (:content b)) 4000)})
                             (when (:is_error b) (swap! out assoc-in [i :error] true)))
                           (when-let [qs (and (not (:is_error b)) (get @asked (:tool_use_id b)))]
                             ;; The result reads: ... "question"="answer" ...
                             (let [answers (into {} (for [[_ q a] (re-seq #"\"((?:[^\"\\\\]|\\\\.)*)\"=\"((?:[^\"\\\\]|\\\\.)*)\"" (result-text (:content b)))]
                                                      [(keyword q) a]))
                                   text (answer-text qs answers)]
                               (when (seq text)
                                 (swap! out conj {:id (str "answer-" (:tool_use_id b)) :role "user" :text text :at at})))))
                       "text" (swap! texts conj (strip-reminders (:text b)))
                       nil))
                   (let [text (str/join "\n" (filter seq @texts))
                         images (count (filter #(= "image" (:type %)) content))]
                     (if-let [notice (task-notice text)]
                       (swap! out conj {:id (:uuid m) :role "note" :text notice :at at})
                       (when (or (seq text) (pos? images))
                         (swap! out conj (cond-> {:id (:uuid m) :role "user" :text text :at at}
                                           ;; GET /api/chats/:id/sent/<this id>/<n> for each
                                           (pos? images) (assoc :images images))))))))
               nil))
           @out)))))
