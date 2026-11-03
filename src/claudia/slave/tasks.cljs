(ns claudia.slave.tasks
  "The work Claude started beside a chat (README decision 25): its subagents and the shells it put in
  the background. Kept from the SDK's task_* messages (and the chat's Agent and Bash calls), saved per
  chat, and read for the page: the agent list (chips), one agent's view (steps, files, prompt,
  report, a shell's output), and progress bars from a shell's `##progress done/total label` lines."
  (:require ["node:fs" :as fs]
            [claudia.shared.text :as text]
            ["node:fs/promises" :as fsp]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.agentgit :as agentgit]
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

(defn loaded
  "Saved tasks as the chat's task map; live? false: its session is gone, so any still marked
  running has stopped (at now, unless it has an end)."
  [saved live? now]
  (into {} (for [t saved]
             [(:id t) (cond-> t
                        (and (not live?) (= "running" (:status t)))
                        (assoc :status "stopped" :end (or (:end t) now)))])))

(defn load!
  "The chat's saved tasks (after a runner start); live? false: its session is gone, so any still
  marked running has stopped."
  [chat live?]
  (when-not (contains? @tasks chat)
    (let [saved (store/kv chat "tasks")]
      (swap! tasks assoc chat (loaded saved live? (js/Date.now))))))

(defn- changed! [chat]
  (save-soon! chat)
  (@on-change chat))

(defn patched
  "The chat's tasks with one changed by m; a finished one that runs again restarts its clock; an
  unknown running one (it started before this runner) is added."
  [ts id m now]
  (let [t (get ts id)]
    (cond
      t (let [again (and (= "running" (:status m)) (not= "running" (:status t)))]
          (assoc ts id (cond-> (merge t m) again (assoc :start now :end nil))))
      (= "running" (:status m)) (assoc ts id (merge {:id id :name "Agent" :type "task" :background true
                                                     :start now :active now} m))
      :else ts)))

(defn- patch! [chat id m]
  (swap! tasks update chat patched id m (js/Date.now))
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

(defn started
  "A task_started message as its Task; call: the chat's own call that started it (its prompt or
  command, kept with the task so it outlives this runner)."
  [m call now]
  (merge
   (when-let [{:keys [input]} call]
     {:call-name (:description input) :command (:command input) :model (:model input)
      :prompt (:prompt input)})
   {:id (:task_id m) :use (:tool_use_id m) :name (or (not-empty (:description m)) "Agent")
    :type (or (:subagent_type m) (:task_type m) "task") :status "running"
    :background (boolean (:is_backgrounded m)) :start now :active now
    :parent (:parent_task_id m) :ambient (boolean (or (:ambient m) (:skip_transcript m)))
    :prompt (or (:prompt m) (get-in call [:input :prompt]))}))

(defn- live-task
  "A task of the live set this runner did not know of."
  [id l now]
  {:id id :name (or (not-empty (:description l)) "Task")
   :type (or (:subagent_type l) (:task_type l) "task")
   :status "running" :background true :start now :active now
   :parent (:parent_task_id l) :ambient (boolean (:ambient l))})

(defn with-live-set
  "The chat's tasks after background_tasks_changed (live: task id -> its entry, the whole live set):
  a running background task missing from it has ended; every one in it is a background task."
  [ts live now]
  (as-> (or ts {}) ts
    (reduce-kv (fn [acc id t]
                 (if (and (= "running" (:status t)) (:background t) (not (contains? live id)))
                   (assoc acc id (assoc t :status "stopped" :end now))
                   acc))
               ts ts)
    (reduce-kv (fn [acc id l]
                 (if-let [t (get acc id)]
                   (assoc acc id (cond-> (assoc t :background true)
                                   (:ambient l) (assoc :ambient true)))
                   (assoc acc id (live-task id l now))))
               ts live)))

(defn progress-patch
  "A task_progress message as the change to its task."
  [m now]
  (cond-> {:status "running" :active now
           :tokens (get-in m [:usage :total_tokens]) :tools (get-in m [:usage :tool_uses])}
    (seq (:description m)) (assoc :name (:description m))
    (:tool_use_id m) (assoc :use (:tool_use_id m))
    (:last_tool_name m) (assoc :last-tool (:last_tool_name m))
    (:summary m) (assoc :summary (:summary m))))

(defn updated-patch
  "A task_updated message as the change to its task."
  [m now]
  (let [pt (:patch m)
        s (status-of (:status pt))]
    (cond-> {}
      s (assoc :status s)
      (and s (not= s "running")) (assoc :end now)
      (seq (:description pt)) (assoc :name (:description pt))
      (some? (:is_backgrounded pt)) (assoc :background (:is_backgrounded pt)))))

(defn notification-patch
  "A task_notification message (the task ended) as the change to its task."
  [m now]
  (cond-> {:status (or (status-of (:status m)) "completed") :end now
           :summary (:summary m) :output (:output_file m)}
    (:usage m) (assoc :tokens (get-in m [:usage :total_tokens]) :tools (get-in m [:usage :tool_uses]))))

(defn on-system!
  "A system message of the chat (task_started, task_progress, task_updated, task_notification,
  background_tasks_changed); true when it was one of them."
  [chat m]
  (let [now (js/Date.now)]
    (case (:subtype m)
      "task_started"
      (do (swap! tasks assoc-in [chat (:task_id m)] (started m (get-in @calls [chat (:tool_use_id m)]) now))
          (changed! chat) true)
      "background_tasks_changed"
      (do (swap! tasks update chat with-live-set (into {} (map (juxt :task_id identity)) (:tasks m)) now)
          (changed! chat) true)
      "task_progress" (do (patch! chat (:task_id m) (progress-patch m now)) true)
      "task_updated" (do (patch! chat (:task_id m) (updated-patch m now)) true)
      "task_notification" (do (patch! chat (:task_id m) (notification-patch m now)) true)
      false)))

(defn stopped!
  "Stop was asked: a task whose agent was stopped never reports its end, so it ends here."
  [chat id]
  (when (= "running" (get-in @tasks [chat id :status]))
    (patch! chat id {:status "stopped" :end (js/Date.now)})))

(defn all-stopped
  "The chat's tasks with every running one stopped at now."
  [ts now]
  (update-vals ts #(if (= "running" (:status %)) (assoc % :status "stopped" :end now) %)))

(defn session-gone!
  "The chat's Claude Code process went: nothing it started still runs."
  [chat]
  (when (some #(= "running" (:status %)) (vals (get @tasks chat)))
    (swap! tasks update chat all-stopped (js/Date.now))
    (changed! chat)))

;; Files --------------------------------------------------------------------------------------

(def ^:private id-ok #"^[\w-]{1,80}$")

(defn- agent-file
  "A subagent's own transcript: subagents/agent-<task id>.jsonl, else the one whose meta names its call."
  [cwd chat t]
  (let [dir (path/join (history/projects-dir cwd) chat "subagents")
        direct (path/join dir (str "agent-" (:id t) ".jsonl"))]
    (cond
      (not (re-matches id-ok (str (:id t)))) nil
      (fs/existsSync direct) direct
      (:use t)
      (some (fn [f]
              (when (str/ends-with? f ".meta.json")
                (let [info (try (p/read-json (fs/readFileSync (path/join dir f) "utf8")) (catch :default _ nil))]
                  (when (= (:use t) (:toolUseId info))
                    (path/join dir (str/replace f #"\.meta\.json$" ".jsonl"))))))
            (try (vec (fs/readdirSync dir)) (catch :default _ []))))))

(defn- agent-meta
  "The subagent's .meta.json beside its transcript (agent type, worktree), or nil."
  [file]
  (when file
    (try (p/read-json (fs/readFileSync (str/replace file #"\.jsonl$" ".meta.json") "utf8"))
         (catch :default _ nil))))

(defn- worktree-of [cwd chat t]
  (let [m (agent-meta (agent-file cwd chat t))]
    (when (and (:worktreePath m) (:worktreeBranch m))
      {:path (:worktreePath m) :branch (:worktreeBranch m)})))

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
                {:done (min (js/parseInt d 10) total) :total total :label (text/cut label 80 "")}))))
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

(defn agent-row
  "One top task as the page's Agent (PROTOCOL.md). call: the chat's own call that started it; shown:
  the chat's counted tasks, by-id the same by id (for its running children)."
  [t call by-id shown]
  (let [shell (shell? t)
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
     :task (when (= "running" (:status t)) (:id t)) :type (:type t) :background (boolean (:background t))
     :model (or (get-in call [:input :model]) (:model t)) :active (or (:active t) (:start t))
     :children kids :progress (:progress t)}))

(defn- agent-order
  "Running first, the latest active first; then the latest ended first."
  [a]
  [(if (= "running" (:status a)) 0 1)
   (- (if (= "running" (:status a)) (:active a) (or (:end a) (:active a))))])

(defn agents
  "The chat's agents and background shells for the page (Agent in PROTOCOL.md), running first."
  [_cwd chat _folder]
  (let [shown (counted (get @tasks chat))
        by-id (into {} (map (juxt :id identity)) shown)
        tops (filter #(= % (top-of % by-id)) shown)]
    (->> tops
         (map #(agent-row % (call-of chat %) by-id shown))
         (sort-by agent-order)
         vec)))

(defn- running-read
  "What a running task shows now that no event tells: a shell's progress (its output), an agent's
  newest step (its file); nil when that did not change."
  [cwd chat folder t now]
  (if (shell? t)
    (let [progress (last-progress (tail (output-file chat t) 16000))]
      (when (not= progress (:progress t)) {:progress progress}))
    (when-let [step (some-> (agent-file cwd chat t) (newest-step folder))]
      (when (not= step (:step t)) {:step step :active now}))))

(defn refresh!
  "Reads what grows without a task event: each running agent's newest step (its file) and each
  running shell's progress (its output). Returns true when something changed."
  [cwd chat folder]
  (let [before (get @tasks chat)
        now (js/Date.now)
        running (for [t (vals before) :when (and (= "running" (:status t)) (not (:ambient t)))]
                  [(:id t) (running-read cwd chat folder t now)])
        ;; A finished shell's last progress line stays on its chip.
        finished (for [t (vals before) :when (and (shell? t) (not= "running" (:status t)) (nil? (:progress-read t)))]
                   [(:id t) {:progress-read true :progress (last-progress (tail (output-file chat t) 16000))}])
        patches (vec (filter second (concat running finished)))]
    (when (seq patches)
      (swap! tasks update chat #(reduce (fn [ts [id m]] (update ts id merge m)) % patches)))
    (not= before (get @tasks chat))))

(defn running-any? [chat] (some #(= "running" (:status %)) (vals (get @tasks chat))))

;; One agent -----------------------------------------------------------------------------------

(defn- edit-of
  "An Edit/Write/MultiEdit call as the texts before and after (numbered within the replaced text)."
  [tool input]
  (case tool
    "Write" [{:before "" :after (str (:content input))}]
    "Edit" [{:before (str (:old_string input)) :after (str (:new_string input))}]
    "MultiEdit" (vec (for [e (:edits input)] {:before (str (:old_string e)) :after (str (:new_string e))}))
    nil))

(defn- file-edits
  "An edit call into its file's entry of the agent's files: lines added and removed counted, the
  edits kept (each text cut at 20000 characters)."
  [f b p edits folder]
  (let [f (or f {:path p :rel (history/shown-path p folder) :added 0 :removed 0 :edits []})
        counts (for [{:keys [before after]} edits] (changes/line-counts before after))]
    (-> f
        (update :added + (reduce + (map :added counts)))
        (update :removed + (reduce + (map :removed counts)))
        (update :edits into (for [{:keys [before after]} edits]
                              {:name (:name b) :before (text/cut before 20000 "\n…") :after (text/cut after 20000 "\n…")})))))

(defn- add-files
  "An assistant entry's edit calls into the agent's files."
  [view content folder]
  (reduce (fn [v b]
            (let [p (or (get-in b [:input :file_path]) (get-in b [:input :notebook_path]))
                  edits (when (= "tool_use" (:type b)) (edit-of (:name b) (:input b)))]
              (if (and p edits)
                (update v :files update p file-edits b p edits folder)
                v)))
          view content))

(defn- add-steps
  "An assistant entry's Msgs into the agent's steps; its last text is what it said last."
  [view e folder]
  (let [at (or (some-> (:timestamp e) js/Date.parse) 0)
        [msgs counts] (history/read-assistant (:message e) (:counts view) at folder)]
    (reduce (fn [v msg]
              (cond-> v
                (= "tool" (:role msg)) (assoc-in [:tools (:id msg)] (count (:steps v)))
                (= "assistant" (:role msg)) (assoc :said (:text msg))
                true (update :steps conj msg)))
            (assoc view :counts counts)
            msgs)))

(defn- add-user
  "A user entry: the first text is the agent's prompt; a failed tool result marks its step."
  [view content]
  (cond
    (string? content) (cond-> view (not (:prompt view)) (assoc :prompt content))
    (sequential? content)
    (reduce (fn [v b]
              (case (:type b)
                "text" (cond-> v (not (:prompt v)) (assoc :prompt (:text b)))
                "tool_result" (if-let [i (and (:is_error b) (get (:tools v) (:tool_use_id b)))]
                                (assoc-in v [:steps i :error] true)
                                v)
                v))
            view content)
    :else view))

(defn- add-entry [folder view e]
  (let [content (get-in e [:message :content])]
    (case (:type e)
      "user" (add-user view content)
      "assistant" (-> view
                      (cond-> (get-in e [:message :model]) (assoc :model (get-in e [:message :model])))
                      (add-files content folder)
                      (add-steps e folder))
      view)))

(defn agent-view
  "A subagent's own file (its text) as steps (Msgs, the last 400), files (edits by file), prompt,
  the last thing it said and its model."
  [text folder]
  (let [v (reduce (partial add-entry folder)
                  {:counts {} :steps [] :tools {} :files (array-map) :prompt nil :said nil :model nil}
                  (jsonl text))]
    {:steps (vec (take-last 400 (:steps v))) :files (vec (vals (:files v)))
     :prompt (:prompt v) :said (:said v) :model (:model v)}))

(defn- read-agent
  "Steps (Msgs), files (edits by file), prompt and the last thing it said, from its own file."
  [file folder]
  (-> (fsp/readFile file "utf8")
      (.then #(agent-view % folder))
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
      (let [file (agent-file cwd chat t)
            wt (worktree-of cwd chat t)]
        (-> (js/Promise.all #js [(if file (read-agent file (or (:path wt) folder)) (js/Promise.resolve nil))
                                 (if wt (agentgit/branch-work (:path wt) (:branch wt)) (js/Promise.resolve nil))])
            (.then (fn [[r work]]
                     {:info (cond-> info (and (:model r) (not (:model info))) (assoc :model (:model r)))
                      :steps (:steps r [])
                      :files (:files r [])
                      :prompt (or (get-in call [:input :prompt]) (:prompt t) (:prompt r))
                      :report (when-not (= "running" (:status t)) (or (:said r) (:summary t)))
                      :worktree wt
                      :git (when work (select-keys work [:branch :base :commits :worktree]))})))))))

(defn agent-git
  "Promise of {:patch} for one agent's worktree branch: rev = a commit of it or \"BRANCHED\"."
  [cwd chat id rev]
  (let [t (get-in @tasks [chat id])
        wt (when t (worktree-of cwd chat t))]
    (if-not wt
      (js/Promise.resolve {:error "This agent has no worktree."})
      (-> (agentgit/branch-work (:path wt) (:branch wt))
          (.then (fn [work] (if work (agentgit/patch work rev) nil)))
          (.then (fn [text] (if text {:patch text} {:error "No such commit on its branch."})))))))

(defn chats-running
  "Chats with a running task."
  []
  (for [[chat m] @tasks :when (some #(= "running" (:status %)) (vals m))] chat))

(defn child?
  "A task some agent of the chat started (not Claude in the chat itself)."
  [chat id]
  (some? (get-in @tasks [chat id :parent])))

(defn background?
  "A task Claude put in the background (its end comes as a notice)."
  [chat id]
  (boolean (get-in @tasks [chat id :background])))
