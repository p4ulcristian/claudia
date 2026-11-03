(ns claudia.slave.tasks
  "The work Claude started beside a chat (README decision 25): its subagents and the shells it put in
  the background. Kept from the SDK's task_* messages (and the chat's Agent and Bash calls), saved per
  chat, and read for the page: the agent list (chips), one agent's view (steps, files, prompt,
  report, a shell's output), and progress bars from a shell's `##progress done/total label` lines."
  (:require ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.changes :as changes]
            [claudia.slave.history :as history]
            [claudia.slave.store :as store]))

;; chat -> {task id -> Task}; Task = {:id :use :name :type :status :background :start :end :tokens
;; :tools :step :parent :ambient :output :summary :active :progress}
(defonce ^:private tasks (atom {}))
;; chat -> {tool_use id -> {:tool :input}}: the chat's own Agent and Bash calls (their prompt or command).
(defonce ^:private calls (atom {}))
;; Set by the chats: (fn [chat]) when a chat's list changed.
(defonce on-change (atom (fn [_])))

(defn- now [] (js/Date.now))

(defn shell? [t] (boolean (re-find #"(?i)bash|shell" (str (:type t)))))

(defn- top-of
  "The task a task belongs to: an agent's own shells and agents are part of it."
  [t by-id]
  (loop [at t n 0]
    (if-let [up (and (< n 20) (:parent at) (get by-id (:parent at)))]
      (recur up (inc n))
      at)))

(defn- counted
  "The tasks shown: not housekeeping, not a foreground shell (part of a tool call of the turn)."
  [m]
  (remove #(or (:ambient %) (and (shell? %) (not (:background %)))) (vals m)))

(defn working
  "How many tops still run (the rail's \"agents working\" count)."
  [chat]
  (let [m (get @tasks chat)
        by-id (into {} (map (juxt :id identity)) (counted m))]
    (count (into #{} (comp (filter #(= "running" (:status %))) (map #(:id (top-of % by-id))))
                 (vals by-id)))))

;; Saving --------------------------------------------------------------------------------------

(defonce ^:private save-timers (atom {}))

(defn- save-soon! [chat]
  (when-not (get @save-timers chat)
    (swap! save-timers assoc chat
           (js/setTimeout (fn []
                            (swap! save-timers dissoc chat)
                            ;; The newest 60 are kept.
                            (store/kv! chat "tasks" (vec (take-last 60 (sort-by :start (vals (get @tasks chat)))))))
                          2000))))

(defn load!
  "The chat's saved tasks (after a runner start); live? false: its session is gone, so any still
  marked running has stopped."
  [chat live?]
  (when-not (contains? @tasks chat)
    (let [saved (store/kv chat "tasks")]
      (swap! tasks assoc chat
             (into {} (for [t saved]
                        [(:id t) (cond-> t
                                   (and (not live?) (= "running" (:status t)))
                                   (assoc :status "stopped" :end (or (:end t) (now))))]))))))

(defn- changed! [chat]
  (save-soon! chat)
  (@on-change chat))

(defn- patch!
  "Changes one task; a finished one that runs again restarts its clock; an unknown running one (it
  started before this runner) is added."
  [chat id m]
  (swap! tasks update chat
         (fn [ts]
           (let [t (get ts id)]
             (cond
               t (let [again (and (= "running" (:status m)) (not= "running" (:status t)))]
                   (assoc ts id (cond-> (merge t m) again (assoc :start (now) :end nil))))
               (= "running" (:status m)) (assoc ts id (merge {:id id :name "Agent" :type "task" :background true
                                                              :start (now) :active (now)} m))
               :else ts))))
  (changed! chat))

;; From the SDK ---------------------------------------------------------------------------------

(defn note-calls!
  "The chat's own assistant message: its Agent and Bash calls are remembered (prompt, command)."
  [chat message]
  (doseq [b (:content message)
          :when (and (= "tool_use" (:type b)) (#{"Agent" "Task" "Bash"} (:name b)))]
    (swap! calls assoc-in [chat (:id b)] {:tool (:name b) :input (:input b)})))

(defn- call-of [chat t] (get-in @calls [chat (:use t)]))

(defn- status-of [s]
  (case s
    ("running" "pending") "running"
    "completed" "completed"
    "failed" "failed"
    ("killed" "stopped") "stopped"
    nil))

(defn on-system!
  "A system message of the chat (task_started, task_progress, task_updated, task_notification,
  background_tasks_changed); true when it was one of them."
  [chat m]
  (case (:subtype m)
    "task_started"
    (do (swap! tasks assoc-in [chat (:task_id m)]
               (merge
                ;; What its call said (kept with the task, so it outlives this runner).
                (when-let [{:keys [input]} (get-in @calls [chat (:tool_use_id m)])]
                  {:call-name (:description input) :command (:command input) :model (:model input)
                   :prompt (:prompt input)})
               {:id (:task_id m) :use (:tool_use_id m) :name (or (not-empty (:description m)) "Agent")
                :type (or (:subagent_type m) (:task_type m) "task") :status "running"
                :background (boolean (:is_backgrounded m)) :start (now) :active (now)
                :parent (:parent_task_id m) :ambient (boolean (or (:ambient m) (:skip_transcript m)))
                :prompt (or (:prompt m) (get-in @calls [chat (:tool_use_id m) :input :prompt]))}))
        (changed! chat) true)
    "background_tasks_changed"
    ;; The whole live set: a running background task missing from it has ended.
    (let [live (into {} (map (juxt :task_id identity)) (:tasks m))]
      (swap! tasks update chat
             (fn [ts]
               (as-> (or ts {}) ts
                 (reduce-kv (fn [acc id t]
                              (if (and (= "running" (:status t)) (:background t) (not (contains? live id)))
                                (assoc acc id (assoc t :status "stopped" :end (now)))
                                acc))
                            ts ts)
                 (reduce-kv (fn [acc id l]
                              (if-let [t (get acc id)]
                                (assoc acc id (cond-> (assoc t :background true)
                                                (:ambient l) (assoc :ambient true)))
                                (assoc acc id {:id id :name (or (not-empty (:description l)) "Task")
                                               :type (or (:subagent_type l) (:task_type l) "task")
                                               :status "running" :background true :start (now) :active (now)
                                               :parent (:parent_task_id l) :ambient (boolean (:ambient l))})))
                            ts live))))
      (changed! chat) true)
    "task_progress"
    (do (patch! chat (:task_id m)
                (cond-> {:status "running" :active (now)
                         :tokens (get-in m [:usage :total_tokens]) :tools (get-in m [:usage :tool_uses])}
                  (seq (:description m)) (assoc :name (:description m))
                  (:tool_use_id m) (assoc :use (:tool_use_id m))
                  (:last_tool_name m) (assoc :last-tool (:last_tool_name m))
                  (:summary m) (assoc :summary (:summary m))))
        true)
    "task_updated"
    (let [pt (:patch m)
          s (status-of (:status pt))]
      (patch! chat (:task_id m)
              (cond-> {}
                s (assoc :status s)
                (and s (not= s "running")) (assoc :end (now))
                (seq (:description pt)) (assoc :name (:description pt))
                (some? (:is_backgrounded pt)) (assoc :background (:is_backgrounded pt))))
      true)
    "task_notification"
    (do (patch! chat (:task_id m)
                (cond-> {:status (or (status-of (:status m)) "completed") :end (now)
                         :summary (:summary m) :output (:output_file m)}
                  (:usage m) (assoc :tokens (get-in m [:usage :total_tokens]) :tools (get-in m [:usage :tool_uses]))))
        true)
    false))

(defn stopped!
  "Stop was asked: a task whose agent was stopped never reports its end, so it ends here."
  [chat id]
  (when (= "running" (get-in @tasks [chat id :status]))
    (patch! chat id {:status "stopped" :end (now)})))

(defn session-gone!
  "The chat's Claude Code process went: nothing it started still runs."
  [chat]
  (when (some #(= "running" (:status %)) (vals (get @tasks chat)))
    (swap! tasks update chat
           (fn [ts] (update-vals ts #(if (= "running" (:status %)) (assoc % :status "stopped" :end (now)) %))))
    (changed! chat)))

;; Files --------------------------------------------------------------------------------------

(defn- projects-dir [cwd]
  (path/join (or (.. js/process -env -CLAUDE_CONFIG_DIR) (path/join (os/homedir) ".claude"))
             "projects" (str/replace cwd #"[^a-zA-Z0-9]" "-")))

(def ^:private id-ok #"^[\w-]{1,80}$")

(defn- agent-file
  "A subagent's own transcript: subagents/agent-<task id>.jsonl, else the one whose meta names its call."
  [cwd chat t]
  (let [dir (path/join (projects-dir cwd) chat "subagents")
        direct (path/join dir (str "agent-" (:id t) ".jsonl"))]
    (cond
      (not (re-matches id-ok (str (:id t)))) nil
      (fs/existsSync direct) direct
      (:use t)
      (some (fn [f]
              (when (str/ends-with? f ".meta.json")
                (let [meta (try (p/read-json (fs/readFileSync (path/join dir f) "utf8")) (catch :default _ nil))]
                  (when (= (:use t) (:toolUseId meta))
                    (path/join dir (str/replace f #"\.meta\.json$" ".jsonl"))))))
            (try (vec (fs/readdirSync dir)) (catch :default _ []))))))

(defonce ^:private output-files (atom {}))

(defn- output-file
  "A shell task's output file: the notification names it once it ends; Claude Code writes it from the
  start at <tmp>/claude-<uid>/<folder>/<session>/tasks/<task>.output."
  [chat t]
  (or (:output t)
      (get @output-files (:id t))
      (when (and (re-matches id-ok (str (:id t))) (re-matches id-ok (str chat)))
        (let [base (path/join (os/tmpdir) (str "claude-" (.getuid js/process)))
              found (some (fn [d]
                            (let [f (path/join base d chat "tasks" (str (:id t) ".output"))]
                              (when (fs/existsSync f) f)))
                          (try (vec (fs/readdirSync base)) (catch :default _ [])))]
          (when found (swap! output-files assoc (:id t) found))
          found))))

(defn- tail
  "The last n bytes of a file as text, or nil."
  [file n]
  (when file
    (try
      (let [fd (fs/openSync file "r")]
        (try
          (let [size (.-size (fs/fstatSync fd))
                len (min size n)
                buf (js/Buffer.alloc len)]
            (fs/readSync fd buf 0 len (- size len))
            (.toString buf "utf8"))
          (finally (fs/closeSync fd))))
      (catch :default _ nil))))

(defn last-progress
  "The last `##progress <done>/<total> [label]` line of a text (a carriage return also ends a line)."
  [text]
  (some (fn [line]
          (when-let [[_ d t label] (re-matches #"##progress (\d+)/(\d+)(?: (.*))?" line)]
            (let [total (js/parseInt t 10)]
              (when (pos? total)
                {:done (min (js/parseInt d 10) total) :total total :label (subs (str label) 0 (min 80 (count (str label))))}))))
        (reverse (str/split (str text) #"[\r\n]+"))))

(defn- jsonl [text]
  (keep #(try (p/read-json %) (catch :default _ nil)) (str/split-lines (str text))))

(defn- newest-step
  "The newest tool call in a subagent's file, as a step: `Edit src/x.cljs`."
  [file folder]
  (some (fn [e]
          (when (= "assistant" (:type e))
            (some (fn [b] (when (= "tool_use" (:type b)) (history/tool-summary (:name b) (:input b) folder)))
                  (reverse (get-in e [:message :content])))))
        (reverse (jsonl (some-> (tail file 65536) (str/replace #"^[^\n]*\n" ""))))))

;; The list ------------------------------------------------------------------------------------

(defn agents
  "The chat's agents and background shells for the page (Agent in PROTOCOL.md), running first."
  [cwd chat folder]
  (let [m (get @tasks chat)
        shown (counted m)
        by-id (into {} (map (juxt :id identity)) shown)
        tops (filter #(= % (top-of % by-id)) shown)]
    (->> tops
         (map (fn [t]
                (let [shell (shell? t)
                      running (= "running" (:status t))
                      call (call-of chat t)
                      kids (count (filter #(and (not= (:id %) (:id t)) (= "running" (:status %))
                                                (= (:id t) (:id (top-of % by-id))))
                                          shown))]
                  {:id (:id t) :kind (if shell "shell" "agent")
                   ;; The Agent or Bash call's own description first: task_progress renames it by what it does now.
                   :name (or (not-empty (get-in call [:input :description])) (not-empty (:call-name t)) (:name t)) :status (:status t)
                   :start (:start t) :end (:end t) :tools (or (:tools t) 0) :tokens (:tokens t)
                   :step (if shell
                           (some->> (or (get-in call [:input :command]) (:command t)) str/split-lines first (str "$ "))
                           (or (:step t) (:last-tool t)))
                   :task (when running (:id t)) :type (:type t) :background (boolean (:background t))
                   :model (or (get-in call [:input :model]) (:model t)) :active (or (:active t) (:start t))
                   :children kids :progress (:progress t)})))
         (sort-by (fn [a] [(if (= "running" (:status a)) 0 1)
                           (- (if (= "running" (:status a)) (:active a) (or (:end a) (:active a))))]))
         vec)))

(defn refresh!
  "Reads what grows without a task event: each running agent's newest step (its file) and each
  running shell's progress (its output). Returns true when something changed."
  [cwd chat folder]
  (let [before (get @tasks chat)]
    (doseq [t (vals before) :when (and (= "running" (:status t)) (not (:ambient t)))]
      (if (shell? t)
        (let [pr (last-progress (tail (output-file chat t) 16000))]
          (when (not= pr (:progress t)) (swap! tasks assoc-in [chat (:id t) :progress] pr)))
        (when-let [step (some-> (agent-file cwd chat t) (newest-step folder))]
          (when (not= step (:step t)) (swap! tasks update-in [chat (:id t)] assoc :step step :active (now))))))
    ;; A finished shell's last progress line stays on its chip.
    (doseq [t (vals before) :when (and (shell? t) (not= "running" (:status t)) (nil? (:progress-read t)))]
      (swap! tasks update-in [chat (:id t)] assoc :progress-read true
             :progress (last-progress (tail (output-file chat t) 16000))))
    (not= before (get @tasks chat))))

(defn running-any? [chat] (some #(= "running" (:status %)) (vals (get @tasks chat))))

;; One agent -----------------------------------------------------------------------------------

(defn- edit-of
  "An Edit/Write/MultiEdit call as the texts before and after (numbered within the replaced text)."
  [name input]
  (case name
    "Write" [{:before "" :after (str (:content input))}]
    "Edit" [{:before (str (:old_string input)) :after (str (:new_string input))}]
    "MultiEdit" (vec (for [e (:edits input)] {:before (str (:old_string e)) :after (str (:new_string e))}))
    nil))

(defn- cut [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "\n…") s)))

(defn- read-agent
  "Steps (Msgs), files (edits by file), prompt and the last thing it said, from its own file."
  [file folder]
  (-> (fsp/readFile file "utf8")
      (.then
       (fn [text]
         (let [counts (atom {})
               steps (atom [])
               tools (atom {})
               files (atom (array-map))
               prompt (atom nil)
               said (atom nil)
               model (atom nil)]
           (doseq [e (jsonl text)
                   :let [content (get-in e [:message :content])]]
             (case (:type e)
               "user"
               (cond
                 (string? content) (when-not @prompt (reset! prompt content))
                 (sequential? content)
                 (doseq [b content]
                   (case (:type b)
                     "text" (when-not @prompt (reset! prompt (:text b)))
                     "tool_result" (when-let [i (and (:is_error b) (get @tools (:tool_use_id b)))]
                                     (swap! steps assoc-in [i :error] true))
                     nil)))
               "assistant"
               (do (when-let [mo (get-in e [:message :model])] (reset! model mo))
                   (doseq [b content
                           :when (= "tool_use" (:type b))
                           :let [p (or (get-in b [:input :file_path]) (get-in b [:input :notebook_path]))
                                 edits (edit-of (:name b) (:input b))]
                           :when (and p edits)]
                     (swap! files update p
                            (fn [f]
                              (let [f (or f {:path p :rel (history/shown-path p folder) :added 0 :removed 0 :edits []})
                                    counted (for [{:keys [before after]} edits]
                                              (let [{:keys [added removed]} (changes/line-counts before after)]
                                                [added removed]))]
                                (-> f
                                    (update :added + (reduce + (map first counted)))
                                    (update :removed + (reduce + (map second counted)))
                                    (update :edits into (for [{:keys [before after]} edits]
                                                          {:name (:name b) :before (cut before 20000) :after (cut after 20000)})))))))
                   (doseq [msg (history/assistant-msgs (:message e) counts (or (some-> (:timestamp e) js/Date.parse) 0) folder)]
                     (when (= "tool" (:role msg)) (swap! tools assoc (:id msg) (count @steps)))
                     (when (= "assistant" (:role msg)) (reset! said (:text msg)))
                     (swap! steps conj msg)))
               nil))
           {:steps (vec (take-last 400 @steps)) :files (vec (vals @files))
            :prompt @prompt :said @said :model @model})))
      (.catch (fn [_] nil))))

(defn detail
  "Promise of one agent's view: {:info (as in the list) :steps :files :prompt :report :output}."
  [cwd chat folder id]
  (let [t (get-in @tasks [chat id])
        info (some #(when (= id (:id %)) %) (agents cwd chat folder))
        call (when t (call-of chat t))]
    (cond
      (nil? t) (js/Promise.resolve {:error "No such agent in this chat."})
      (shell? t)
      (js/Promise.resolve {:info info
                           :command (or (get-in call [:input :command]) (:command t))
                           :output (tail (output-file chat t) 65536)})
      :else
      (let [file (agent-file cwd chat t)]
        (-> (if file (read-agent file folder) (js/Promise.resolve nil))
            (.then (fn [r]
                     {:info (cond-> info (and (:model r) (not (:model info))) (assoc :model (:model r)))
                      :steps (:steps r [])
                      :files (:files r [])
                      :prompt (or (get-in call [:input :prompt]) (:prompt t) (:prompt r))
                      :report (when-not (= "running" (:status t)) (or (:said r) (:summary t)))})))))))

(defn chats-running
  "Chats with a running task."
  []
  (for [[chat m] @tasks :when (some #(= "running" (:status %)) (vals m))] chat))

(defn child?
  "A task some agent of the chat started (not Claude in the chat itself)."
  [chat id]
  (some? (get-in @tasks [chat id :parent])))
