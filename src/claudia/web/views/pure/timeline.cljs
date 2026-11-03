(ns claudia.web.views.pure.timeline
  "The timeline's feed as plain data: durations, a tool's category, the rows shown (filtered to one
  agent and the agents it started), a rail per agent, the dividers between rows, a patch's lines.")

(def palette ["sky" "violet" "teal" "pink" "amber" "lime" "orange" "indigo" "cyan" "magenta"
              "green" "red" "yellow" "purple" "blue" "brown"])

(defn dur
  "A duration: 0.4s, 12s, 3m 5s, 2h 10m, 1d 3h."
  [ms]
  (let [s (js/Math.round (/ ms 1000))
        m (js/Math.floor (/ s 60))
        hr (js/Math.floor (/ m 60))]
    (cond (< s 60) (if (< ms 1000) (str (.toFixed (/ ms 1000) 1) "s") (str s "s"))
          (< m 60) (str m "m " (mod s 60) "s")
          (< hr 24) (str hr "h " (mod m 60) "m")
          :else (str (js/Math.floor (/ hr 24)) "d " (mod hr 24) "h"))))

(defn category
  "What kind of tool: read, edit, shell, web, agent or other."
  [tool]
  (condp re-matches tool
    #"Read|Grep|Glob|LS|ToolSearch|NotebookRead" "read"
    #"Edit|Write|NotebookEdit|MultiEdit" "edit"
    #"Bash|Monitor|TaskStop|PowerShell" "shell"
    #"WebFetch|WebSearch" "web"
    #"Agent|Task|SendMessage|Workflow" "agent"
    "other"))

(defn agent-colors
  "Each agent lane's colour, in turn from the palette."
  [agents]
  (into {} (map-indexed (fn [i l] [(:id l) (nth palette (mod i (count palette)))])) agents))

(defn kept-lanes
  "The lanes shown when only is picked: the chat alone, or a lane and every lane under it (lanes
  come parent first). nil: all of them."
  [lanes only]
  (when only
    (if (= only "main")
      #{"main"}
      (reduce (fn [s l] (if (contains? s (:parent l)) (conj s (:id l)) s)) #{only} lanes))))

(defn all-rows
  "Every event of the kept lanes as a row {:lane :e :at}, in time order."
  [lanes kept]
  (->> (for [lane lanes :when (or (nil? kept) (kept (:id lane))) e (:events lane)]
         {:lane lane :e e :at (if (= "tool" (:k e)) (:start e) (:at e))})
       (sort-by :at)
       vec))

(defn last-rows
  "The last shown rows."
  [rows shown]
  (if (> (count rows) shown) (subvec rows (- (count rows) shown)) rows))

(defn- index-of-first [rows]
  (reduce (fn [m [i r]] (if (contains? m (get-in r [:lane :id])) m (assoc m (get-in r [:lane :id]) i)))
          {} (map-indexed vector rows)))

(defn- index-of-last [rows]
  (reduce (fn [m [i r]] (assoc m (get-in r [:lane :id]) (if (get-in r [:lane :running]) (count rows) i)))
          {} (map-indexed vector rows)))

(defn- take-rail
  "The rail lane id takes: the chat's is 0; another takes the first free one at or after low."
  [[col free] id first-at last-at low]
  (let [f (first-at id)
        c (if (= id "main")
            0
            (or (first (keep-indexed (fn [i end] (when (and (>= i low) (< end f)) i)) free))
                (max low (count free))))
        free (if (< c (count free)) (assoc free c (last-at id))
                 (conj (into free (repeat (- c (count free)) -1)) (last-at id)))]
    [(assoc col id c) free]))

(defn rails-of
  "Each lane holds one rail from its first row to its last (to the bottom while it runs); a free
  rail is taken by a later lane. The chat keeps the first rail when it is shown."
  [rows]
  (let [first-at (index-of-first rows)
        last-at (index-of-last rows)
        low (if (contains? first-at "main") 1 0)
        [col free] (reduce #(take-rail %1 %2 first-at last-at low) [{} []] (sort-by first-at (keys first-at)))]
    {:first first-at :last last-at :col col :count (max 1 (count free))}))

(defn divider
  "What goes between the row before (prev) and row: :day when the day changed, :quiet after more
  than idle ms, else nil. day-of gives a time's day."
  [prev row idle day-of]
  (cond (and prev (not= (day-of (:at prev)) (day-of (:at row)))) :day
        (and prev (> (- (:at row) (:at prev)) idle)) :quiet))

(defn- patch-line [[o n] line]
  (let [c (subs line 0 1)
        text (subs line 1)]
    (case c
      "-" [[(inc o) n] {:kind "del" :a o :b nil :text text}]
      "+" [[o (inc n)] {:kind "add" :a nil :b n :text text}]
      "\\" [[o n] {:kind "meta" :a nil :b nil :text text}]
      [[(inc o) (inc n)] {:kind "ctx" :a o :b n :text text}])))

(defn hunk-rows
  "A patch hunk's lines as rows {:kind :a :b :text}: a (old) and b (new) are the line numbers."
  [{:keys [oldStart newStart lines]}]
  (second (reduce (fn [[at out] line]
                    (let [[at' row] (patch-line at line)] [at' (conj out row)]))
                  [[oldStart newStart] []] lines)))

(defn agents-summary
  "The head's line: how many agents, how many running."
  [agents]
  (let [running (count (filter :running agents))]
    (if (seq agents)
      (str (count agents) (if (= 1 (count agents)) " agent" " agents")
           (when (pos? running) (str " · " running " running")))
      "no agents yet")))

(defn step-class
  "A row's class: a tool step by how it went (err, busy, lost or none), anything else by its kind."
  [lane e]
  (if (= "tool" (:k e))
    (cond (:error e) "err" (nil? (:end e)) (if (:running lane) "busy" "lost") :else "")
    (:k e)))

(defn who
  "Who a message row is from."
  [lane e]
  (case (:k e) "user" (if (= "main" (:kind lane)) "You" "Task") "note" "Note" "Claude"))
