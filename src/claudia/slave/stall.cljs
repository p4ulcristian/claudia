(ns claudia.slave.stall
  "The stall watch (README decision 111), as plain data: a running agent that shows no sign of work,
  and none of its running work does either, for quiet-ms is stalled: it waits on something that no
  longer moves (2026-11-21: an agent waited 16 minutes on a shell whose `while pgrep -f …` loop
  matched its own command line, so it never ended and never printed). A task here is the slave's
  Task with :shell (whether it is a shell).")

(def quiet-ms
  "How long an agent and all its running work may show no sign before it counts as stalled: over
  the 10 minutes a foreground command may run (the Bash tool's longest timeout), so one long
  command alone never trips it."
  (* 12 60000))

(defn sign
  "When a task last showed it was at work, ms: a shell's output last written (:heard), an agent's
  newest step or progress (:active); else when it started."
  [{:keys [shell heard active start]}]
  (or (if shell heard active) start 0))

(defn quiet-since
  "When an agent t or any of its running work (kids) last showed a sign, ms."
  [t kids]
  (reduce max (sign t) (map sign kids)))

(defn stalled-since
  "When a running agent t went quiet, ms, when it and all its running work (kids) have shown no
  sign for quiet-ms at now; else nil (a shell is never stalled on its own: a server may rightly
  print nothing for hours)."
  [t kids now]
  (when (and (= "running" (:status t)) (not (:shell t)))
    (let [since (quiet-since t kids)]
      (when (>= (- now since) quiet-ms) since))))

(defn minutes "Whole minutes in ms." [ms] (js/Math.floor (/ ms 60000)))

(defn- kid-words
  "One piece of running work of a stalled agent, in words."
  [{kid-name :name :keys [shell]}]
  (if shell
    (str "its shell `" kid-name "` still runs but has printed nothing")
    (str "its agent \"" kid-name "\" has taken no step")))

(defn note-text
  "What the chat that started the agent named agent-name is told when it stalled (kids: its
  running work, each {:shell :name}; mins: how long it has been quiet)."
  [agent-name kids mins]
  (str "The agent \"" agent-name "\" has shown no sign of work for " mins " min: no step of its own, and "
       (if (seq kids)
         (->> kids (map kid-words) (interpose ", ") (apply str))
         "nothing of its own still runs")
       ". Check that its work still runs (a process alive, a log growing); if it does not, stop"
       " the agent or tell it what happened."))
