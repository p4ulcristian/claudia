(ns claudia.web.views.pure.agents
  "The work Claude started beside the chat, as plain data: small words, which chips fit under the
  title, one agent's facts and tabs.")

(defn dur
  "Elapsed time: 42s, 3m 5s, 1h 4m."
  [ms]
  (let [s (max 0 (js/Math.floor (/ ms 1000)))]
    (cond (< s 60) (str s "s")
          (< s 3600) (str (quot s 60) "m " (mod s 60) "s")
          :else (str (quot s 3600) "h " (quot (mod s 3600) 60) "m"))))

(defn tokens
  "Tokens only (no cost, no share of a limit): 950, 155k, 1.2M."
  [n]
  (cond (nil? n) ""
        (< n 1000) (str n)
        (< n 1e6) (str (js/Math.round (/ n 1000)) "k")
        :else (str (.toFixed (/ n 1e6) 1) "M")))

(defn running? [a] (= "running" (:status a)))

(defn elapsed
  "How long it ran (until now while it runs)."
  [a now]
  (- (or (:end a) now) (:start a)))

(def chip-min 250)
(def gap 6)

(defn chip-row
  "What the chips under the title show, the row being width px wide: the running subagents and
  background shells (as many as fit: up to four on a desktop, two on a phone, the most active),
  how many more, the finished and failed counts, and the grid's columns."
  [agents width phone?]
  (let [running (filter #(and (running? %) (or (= "agent" (:kind %)) (:background %))) agents)
        finished (remove running? agents)
        failed (count (filter #(= "failed" (:status %)) finished))
        cols (max 1 (js/Math.floor (/ (+ width gap) (+ chip-min gap))))
        room (cond phone? 2 (= cols 1) 3 :else (min 4 (* cols 2)))
        shown (->> running (sort-by #(- (:active %))) (take room) (sort-by :start))]
    {:shown shown
     :more (- (count running) (count shown))
     :finished finished
     :failed failed
     :done (- (count finished) failed)
     :columns (min cols (count shown) (if phone? 2 4))}))

(defn facts-words
  "A chip's facts: time, tools (a subagent), tokens."
  [a now]
  (remove nil? [(dur (elapsed a now))
                (when (= "agent" (:kind a)) (str (:tools a) (if (= 1 (:tools a)) " tool" " tools")))
                (when (:tokens a) (tokens (:tokens a)))]))

(defn tabs
  "One agent's tabs [id label]: a shell's Output and Command; a subagent's Steps, Files, Prompt,
  Report."
  [shell? files]
  (if shell?
    [["steps" "Output"] ["prompt" "Command"]]
    [["steps" "Steps"] ["files" (if (seq files) (str "Files " (count files)) "Files")]
     ["prompt" "Prompt"] ["report" "Report"]]))

(defn- worktree-fact [{:keys [worktree git]}]
  (when worktree
    ["Worktree" (str (:branch worktree)
                     (when git
                       (let [n (count (:commits git))] (str " · " n (if (= 1 n) " commit" " commits")))))]))

(defn facts
  "One agent's facts [label value]; d is its detail from the runner."
  [a d now]
  (let [shell? (= "shell" (:kind a))]
    (remove nil?
            [[(if (running? a) "Running for" "Took") (dur (elapsed a now))]
             (when-not shell? ["Tools" (str (:tools a))])
             (when (:tokens a) ["Tokens" (tokens (:tokens a))])
             (when (:model a) ["Model" (:model a)])
             ["Kind" (if shell? "background shell" (str (:type a) (when (:background a) " · background")))]
             (worktree-fact d)])))

(defn status-chip
  "The status chip's colour and word."
  [status]
  [(case status "failed" "red" "running" "amber" "completed" "green" "")
   (case status "completed" "done" status)])

(defn merged-info
  "The agent as shown: its detail's info, overlaid by what the chat's state says (nil values left
  out); nil when neither is known."
  [info d]
  (when (or info (:info d))
    (merge (:info d) (into {} (remove (comp nil? val)) info))))
