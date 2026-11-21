(ns claudia.node.events
  "System's log (README decision 96, PROTOCOL.md `Event`): pure functions over the journal's JSON
  lines (out-of-memory kills, systemd-oomd kills, failed units), `coredumpctl list --json=short`
  and the kernel's modules folder (a reboot needed)."
  (:require [clojure.string :as str]
            [claudia.shared.protocol :as p]))

(def message-ids
  "The journal's catalog ids System reads: a unit failed, the kernel's OOM killer took a process of
  a unit, systemd-oomd killed processes of a unit."
  {"d9b373ed55a64feb8242e02dbe79a49c" "failed"
   "fe6faa94e7774663a0da52717891d8ef" "oom"
   "d989611b15e44c9dbf31e3c81256e4ed" "oom"})

(def week-ms (* 7 24 60 60 1000))

(def max-events 200)

(defn- json-line [line]
  (let [v (try (p/read-json line) (catch :default _ nil))] (when (map? v) v)))

(defn- journal-ms [entry]
  (some-> (:__REALTIME_TIMESTAMP entry) js/Number (/ 1000) js/Math.round))

(defn journal-event
  "One journal entry (a map) as an Event, nil for an entry of another kind."
  [entry]
  (when-let [kind (get message-ids (:MESSAGE_ID entry))]
    (let [unit (or (not-empty (:USER_UNIT entry)) (not-empty (:UNIT entry)))]
      (cond-> {:at (journal-ms entry) :kind kind :text (str (:MESSAGE entry))}
        unit (assoc :unit unit)))))

(defn journal-events
  "The journal read's text (JSON lines) as Events."
  [text]
  (vec (keep (comp #(some-> % journal-event) json-line) (str/split-lines (str text)))))

(defn- program-name [exe] (last (str/split (str exe) #"/")))

(defn coredump-events
  "`coredumpctl list --json=short`'s text as crash Events: the program's name (never its path or
  command line), its pid and signal."
  [text]
  (let [v (try (p/read-json text) (catch :default _ []))]
    (vec (for [d (if (sequential? v) v [])
               :let [prog (program-name (:exe d))]]
           {:at (js/Math.round (/ (:time d) 1000)) :kind "crash" :name prog :pid (:pid d)
            :text (str prog " crashed (signal " (:sig d) ")")}))))

(defn reboot-event
  "A reboot Event when the running kernel's release is not among the modules folder's names (an
  update replaced it: new drivers cannot load until a reboot), at: when the folder changed; else nil."
  [release module-dirs at]
  (when (and (seq module-dirs) (not (some #{release} module-dirs)))
    {:at at :kind "reboot" :text (str "A reboot is needed: the running kernel " release " was replaced by an update")}))

(defn newest
  "Events of the last 7 days before now, newest first, at most max-events."
  [events now]
  (->> events
       (filter #(and (:at %) (> (:at %) (- now week-ms))))
       (sort-by (comp - :at))
       (take max-events)
       vec))

(defn log-lines
  "A unit's journal lines (`journalctl -o json --output-fields=MESSAGE,PRIORITY`) as [{:at :prio
  :text}], oldest first."
  [text]
  (vec (for [e (keep json-line (str/split-lines (str text)))]
         {:at (journal-ms e) :prio (some-> (:PRIORITY e) js/parseInt) :text (str (:MESSAGE e))})))
