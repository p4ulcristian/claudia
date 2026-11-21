(ns claudia.web.views.pure.system-look
  "\"Needs a look\" and each server's dot on System (README decision 96), pure: only problems and the
  chats' proposals, each a row with its facts and its actions, and the dot they make: red = a failed
  unit, a crash or an out-of-memory kill in the last hour; amber = a port open on all addresses that
  nothing owns, a leftover, memory or a disk over 85%, waiting over 10%; green otherwise; grey = not
  reached."
  (:require [clojure.string :as str]
            [claudia.web.format :as format]
            [claudia.web.views.pure.system-lists :as lists]
            [claudia.web.views.pure.system-tiles :as tiles]))

(def hour-ms 3600000)
(def near-cap "Memory or a disk over this share (percent) needs a look." 85)
(def waiting-limit "Waiting over this share (percent) needs a look." 10)

;; ---- the rows, by what they are about ----

(defn- unit-of [{:keys [scope user] unit :name}] {:unit unit :scope scope :user user})

(defn failed-rows
  "A row per failed service: red, with its journal (Log)."
  [services]
  (vec (for [s services :when (= "failed" (:state s))]
         {:id (str "failed " (:scope s) " " (:user s) " " (:name s)) :tone "red" :subject (:name s)
          :text "failed" :at (:since s) :at-word "since" :unit (unit-of s)
          :facts (remove nil? [(if (= "user" (:scope s)) (str (:user s) "'s user unit") "system unit")
                               (when (pos? (or (:restarts s) 0)) (str "restarted " (:restarts s) " times"))
                               (not-empty (:desc s))])})))

(def ^:private event-words
  {"oom" "was killed: out of memory" "crash" "crashed (core dump)" "failed" "failed, running again"})

(defn event-rows
  "A row per crash, out-of-memory kill or failure in the last hour (a unit still failed has its own
  row), red."
  [events services now]
  (let [failed (set (keep #(when (= "failed" (:state %)) (:name %)) services))]
    (vec (for [e events
               :when (and (< (- now (:at e)) hour-ms) (event-words (:kind e))
                          (not (and (= "failed" (:kind e)) (failed (:unit e)))))]
           {:id (str "event " (:kind e) " " (:at e) " " (:pid e)) :tone "red" :at (:at e) :at-word "at"
            :subject (or (:name e) (:unit e) "a process") :text (event-words (:kind e))
            :facts (remove nil? [(:text e) (when (:pid e) (str "pid " (:pid e)))])
            :unit (when (:unit e) {:unit (:unit e) :scope "system"})}))))

(defn- unowned?
  "A port open on all addresses that no unit, container or chat owns and no label names; without
  root a port of another user shows no process, which says nothing."
  [labels server root? p]
  (and (= "all" (:reach p))
       (not (lists/owned? (:owner p)))
       (or (:pid p) root?)
       (not (lists/label-for labels server (lists/label-keys p)))))

(defn port-rows
  "A row per listener open on all addresses that nothing owns, amber."
  [ports labels server root?]
  (vec (for [p (lists/port-rows ports) :when (unowned? labels server root? p)]
         {:id (str "port " (:proto p) " " (:port p) " " (:pid p)) :tone "amber" :subject (str ":" (:port p))
          :text "is open on all addresses, and nothing owns it"
          :facts (remove nil? [(:proto p) (if (:name p) (str (:name p) " (pid " (:pid p) ")") "no process seen")
                               (lists/owner-text (:owner p))])})))

(defn leftover-rows
  "A row per leftover (in a Claudia host, started by no running chat), amber."
  [leftovers]
  (vec (for [p leftovers]
         {:id (str "leftover " (:pid p)) :tone "amber" :subject (:name p)
          :text "was left behind by a chat that ended"
          :facts (remove nil? [(when (:age p) (lists/old-text (:age p))) (format/bytes-text (:rss p))
                               (str "pid " (:pid p)) (some-> (get-in p [:owner :name]) (str "'s chats"))])})))

(defn- memory-row [memory]
  (let [{:keys [percent used total]} (tiles/memory-tile memory)]
    (when (> percent near-cap)
      {:id "memory" :tone "amber" :subject "Memory" :text (str percent "% used, near the " tiles/memory-cap "% cap")
       :facts [(str (format/bytes-text used) " of " (format/bytes-text total))]})))

(defn- disk-row [d]
  (let [p (tiles/disk-share d)]
    (when (> p near-cap)
      {:id (str "disk " (:mount d)) :tone "amber" :subject (:mount d) :text (str p "% full")
       :facts [(str (format/bytes-text (:avail d)) " free") (:source d)]})))

(defn- waiting-row [pressure]
  (let [rows (tiles/waiting-rows pressure)
        worst (last (sort-by :value rows))]
    (when (and worst (> (:value worst) waiting-limit))
      {:id "waiting" :tone "amber" :subject (str "Programs wait " (:label worst))
       :text (str (js/Math.round (:value worst)) "% of the time")
       :facts (for [r rows :when (not= r worst)] (str (:label r) " " (js/Math.round (:value r)) "%"))})))

(defn cap-rows
  "Memory or a disk over 85%, waiting over 10%: a row each, amber."
  [{:keys [memory disks pressure]}]
  (vec (remove nil? (concat [(when memory (memory-row memory))] (map disk-row disks) [(waiting-row pressure)]))))

(defn proposal-rows
  "A row per chat's proposal: violet, Accept (admins) and No."
  [proposals]
  (vec (for [p proposals]
         {:id (str "proposal " (:runner p) " " (:id p)) :tone "violet" :proposal p :chat (:chat p)
          :subject (or (get-in p [:chat :title]) "A chat")
          :text (str "proposes the project “" (:project p) "” for " (:kind p) " " (:match p))
          :facts (remove str/blank? [(:why p)]) :at (:at p)})))

;; ---- all of it ----

(def ^:private tone-rank {"red" 0 "amber" 1 "violet" 2})

(defn problems
  "Everything about one server that needs a look, red first: from its Sample, Details and the labels."
  [{:keys [sample details labels server now]}]
  (->> (concat (failed-rows (:services details))
               (event-rows (:log details) (:services details) now)
               (port-rows (:ports details) labels server (:root details))
               (leftover-rows (:leftovers details))
               (when sample (cap-rows sample)))
       (sort-by #(tone-rank (:tone %)))
       vec))

(defn attention
  "The \"Needs a look\" rows: the problems, then the chats' proposals."
  [feed proposals]
  (into (problems feed) (proposal-rows proposals)))

(defn health
  "A server's dot: grey when not reached (no Sample, or the last ask failed), else red or amber by
  its worst problem, else green."
  [{:keys [sample error] :as feed}]
  (if (or (nil? sample) error)
    "grey"
    (or (:tone (first (problems feed))) "green")))

(defn health-tip [h]
  (get {"grey" "not reached" "red" "something failed or crashed in the last hour"
        "amber" "something needs a look" "green" "all quiet"} h))

(defn ask-text
  "What Ask a chat puts in the composer: the row's facts, short and plain. clock: the row's time in
  words (\"14:02\"), or nil."
  [server row clock]
  (let [facts (remove str/blank? (concat (:facts row) [(when (and clock (:at-word row)) (str (:at-word row) " " clock))]))]
    (str "System on " server ": " (:subject row) " " (:text row)
         (when (seq facts) (str " (" (str/join " · " facts) ")")) ".")))
