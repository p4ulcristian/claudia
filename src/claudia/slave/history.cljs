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
      (if (str/starts-with? tool "mcp__")
        (str/join " " (rest (str/split tool #"__")))
        tool))))

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
                    "tool_use" {:id (:id b) :role "tool" :tool (:name b)
                                :summary (tool-summary (:name b) (:input b) folder) :at at}
                    nil)))
          (:content message))))

(defn session-file [cwd id]
  (path/join (or (.. js/process -env -CLAUDE_CONFIG_DIR) (path/join (os/homedir) ".claude"))
             "projects" (str/replace cwd #"[^a-zA-Z0-9]" "-") (str id ".jsonl")))

(defn- timestamps
  "uuid -> ms, from the session file (the SDK's session messages carry no time)."
  [cwd id]
  (-> (fsp/readFile (session-file cwd id) "utf8")
      (.then (fn [text]
               (reduce (fn [m line]
                         (try (let [^js e (js/JSON.parse line)]
                                (if (and (.-uuid e) (.-timestamp e))
                                  (assoc m (.-uuid e) (js/Date.parse (.-timestamp e)))
                                  m))
                              (catch :default _ m)))
                       {} (str/split-lines text))))
      (.catch (fn [_] {}))))

(defn load
  "Promise of the chat's Msgs, oldest first."
  [cwd id folder]
  (-> (js/Promise.all #js [(sdk/getSessionMessages id #js {:dir cwd}) (timestamps cwd id)])
      (.then
       (fn [[msgs times]]
         (let [counts (atom {})
               out (atom [])
               tools (atom {})   ; tool_use id -> index in out
               asked (atom {})]  ; AskUserQuestion tool_use id -> questions
           (doseq [m (js->clj msgs :keywordize-keys true)
                   :when (nil? (:parent_tool_use_id m))
                   :let [at (get times (:uuid m) 0)
                         message (:message m)
                         content (:content message)]]
             (case (:type m)
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
                       (do (when-let [i (and (:is_error b) (get @tools (:tool_use_id b)))]
                             (swap! out assoc-in [i :error] true))
                           (when-let [qs (and (not (:is_error b)) (get @asked (:tool_use_id b)))]
                             ;; The result reads: ... "question"="answer" ...
                             (let [answers (into {} (for [[_ q a] (re-seq #"\"((?:[^\"\\\\]|\\\\.)*)\"=\"((?:[^\"\\\\]|\\\\.)*)\"" (result-text (:content b)))]
                                                      [(keyword q) a]))
                                   text (answer-text qs answers)]
                               (when (seq text)
                                 (swap! out conj {:id (str "answer-" (:tool_use_id b)) :role "user" :text text :at at})))))
                       "text" (swap! texts conj (strip-reminders (:text b)))
                       "image" (swap! texts conj "[image]")
                       nil))
                   (let [text (str/join "\n" (filter seq @texts))]
                     (if-let [notice (task-notice text)]
                       (swap! out conj {:id (:uuid m) :role "note" :text notice :at at})
                       (when (seq text) (swap! out conj {:id (:uuid m) :role "user" :text text :at at}))))))
               nil))
           @out)))))
