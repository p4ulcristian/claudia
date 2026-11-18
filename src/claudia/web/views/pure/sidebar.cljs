(ns claudia.web.views.pure.sidebar
  "The chat list and the rail as plain data: which chats a search keeps, the order on the rail, a
  row's second line, how long ago."
  (:require [clojure.string :as str]
            [claudia.shared.text :as text]))

(defn search-key
  "The search text as compared: trimmed, lower case."
  [s]
  (str/lower-case (str/trim (str s))))

(defn project-key
  "The project a row is filed under: its project's root, \"\" for none."
  [row]
  (or (get-in row [:project :root]) ""))

(defn project-label
  "A row's project as its tag and chip say it."
  [row]
  (or (get-in row [:project :name]) "No project"))

(defn- holds? [s q] (str/includes? (str/lower-case (str s)) q))

(defn project-holds?
  "Whether the row's project name holds q (a search-key)."
  [row q]
  (boolean (and (seq q) (get-in row [:project :name]) (holds? (get-in row [:project :name]) q))))

(defn matching
  "The rows whose title or project name holds q (a search-key); all of them for an empty q."
  [rows q]
  (if (seq q) (filter #(or (holds? (:title %) q) (project-holds? % q)) rows) rows))

(defn in-project
  "The rows of the project chip k (a project-key; nil: all)."
  [rows k]
  (if (nil? k) rows (filter #(= k (project-key %)) rows)))

(defn project-chips
  "The archived list's project chips, [{:key :label :count}]: most chats first, then by name; the
  chats with no project last."
  [rows]
  (->> (group-by project-key rows)
       (map (fn [[k rs]] {:key k :label (project-label (first rs)) :count (count rs)}))
       (sort-by (juxt #(= "" (:key %)) (comp - :count) (comp str/lower-case :label)))
       vec))

(def ^:private weekdays ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])
(def ^:private months ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn short-ago
  "How long ago ms (epoch) was at now, short: \"now\", \"12m\", \"2h\", the weekday within a
  week, else \"12 Nov\"."
  [ms now]
  (let [d (- now ms) m (quot d 60000) h (quot m 60)]
    (cond
      (< m 1) "now"
      (< m 60) (str m "m")
      (< h 24) (str h "h")
      (< h (* 24 7)) (weekdays (.getDay (js/Date. ms)))
      :else (let [t (js/Date. ms)] (str (.getDate t) " " (months (.getMonth t)))))))

(defn- clock
  "ms as a running clock: \"0:07\", \"4:12\", \"1:02:05\"."
  [ms]
  (let [s (quot (max 0 ms) 1000) two #(if (< % 10) (str "0" %) (str %))
        h (quot s 3600) m (quot (mod s 3600) 60)]
    (if (pos? h) (str h ":" (two m) ":" (two (mod s 60))) (str m ":" (two (mod s 60))))))

(defn- agents-text [n] (str n " agent" (when (not= n 1) "s")))

(defn status-line
  "The rail's line under a chat's name (README decision 46), [kind text]: running (how long, and
  its agents), asks (a question waits on you, how long), done (finished since you looked, how
  long ago), quiet (last active). runningSince / askedAt are page times (with-clock-times)."
  [{:keys [waiting running agents runningSince askedAt ended at]} done? now]
  (let [n (or agents 0)]
    (cond
      waiting ["asks" (str "? asks" (when askedAt (str " \u00b7 " (short-ago askedAt now))))]
      (and running runningSince) ["running" (str (clock (- now runningSince)) (when (pos? n) (str " \u00b7 " (agents-text n))))]
      (or running (pos? n)) ["running" (if (pos? n) (agents-text n) "working")]
      (and done? (pos? ended)) ["done" (str "done \u00b7 " (short-ago ended now))]
      (pos? at) ["quiet" (let [a (short-ago at now)] (if (re-find #"\d[mh]$" a) (str a " ago") a))]
      :else ["quiet" ""])))

(defn done-mark?
  "Whether a chat's circle carries the big check (it finished since you looked: the status line's
  done, nothing running or waiting)."
  [r done?]
  (= "done" (first (status-line r done? 0))))

(defn with-clock-times
  "A server's chat rows as they came at now (page time): runningFor and the asks' ago become the page
  times runningSince and askedAt, so a clock can run on without a new frame."
  [chats now]
  (mapv (fn [{:keys [runningFor asks] :as c}]
          (cond-> c
            runningFor (assoc :runningSince (- now runningFor))
            (seq asks) (assoc :askedAt (- now (apply max (map #(or (:ago %) 0) asks))))))
        chats))

(defn recent-archived
  "The n archived rows last active, newest first: the search panel's list before a word is typed
  (a row keeps no time of its archiving; its last activity is the nearest)."
  [rows n]
  (->> rows (filter :archived) (sort-by :at >) (take n) vec))

(defn found-rows
  "The rows of a search's finds ({server [Found]}), as rows-by ([server id] -> row) has them."
  [found rows-by]
  (vec (keep (fn [[server f]] (rows-by [server (:id f)])) (for [[server fs] found, f fs] [server f]))))

(defn with-project-finds
  "The search's finds ({server [Found]}) with every row whose project name holds q added (no hits)
  when the search did not find it already."
  [found rows q]
  (let [have (set (for [[server fs] found, f fs] [server (:id f)]))]
    (reduce (fn [acc r]
              (if (or (have [(:server r) (:id r)]) (not (project-holds? r q)))
                acc
                (update acc (:server r) (fnil conj []) {:id (:id r) :count 0 :hits []})))
            found rows)))

(defn- day-start [ms]
  (let [d (js/Date. ms)] (.getTime (js/Date. (.getFullYear d) (.getMonth d) (.getDate d)))))

(defn days-ago
  "How many calendar days (local) before now t is."
  [t now]
  (js/Math.round (/ (- (day-start now) (day-start t)) 86400000)))

(defn- month-label [t now]
  (let [d (js/Date. t)]
    (str (.toLocaleDateString d "en-US" #js {:month "long"})
         (when (not= (.getFullYear d) (.getFullYear (js/Date. now))) (str " " (.getFullYear d))))))

(defn time-section
  "The archived list's section for a chat last active at t: Today, Yesterday, Past 7 days, Past 2
  weeks, then its month (with the year when it is not now's)."
  [t now]
  (let [n (days-ago (or t 0) now)]
    (cond (<= n 0) "Today"
          (= n 1) "Yesterday"
          (< n 7) "Past 7 days"
          (< n 14) "Past 2 weeks"
          :else (month-label (or t 0) now))))

(defn in-sections
  "items newest first (at-of: an item's time), cut into [section items] by time-section, in order."
  [items at-of now]
  (let [section #(time-section (at-of %) now)]
    (->> (sort-by #(- (or (at-of %) 0)) items)
         (partition-by section)
         (mapv (fn [part] [(section (first part)) (vec part)])))))

(defn passive?
  "A chat that steps back (dimmed, at the bottom): coloured passive ash, or open in another tab of
  this browser (elsewhere: [server chat] keys) while not the chat open here (open)."
  [row elsewhere open]
  (let [k [(:server row) (:id row)]]
    (or (= "ash" (:color row))
        (and (contains? elsewhere k) (not= k open)))))

(defn passive-last
  "Passive chats last (passive?), each part in its own order."
  ([rows] (passive-last rows #{} nil))
  ([rows elsewhere open]
   (let [p #(passive? % elsewhere open)]
     (vec (concat (remove p rows) (filter p rows))))))

(defn ago-text
  "How long ago t was at now: now, 5m, 3h, 2d; nil past a week (the date shows then)."
  [t now]
  (let [s (/ (- now t) 1000)]
    (cond (< s 60) "now"
          (< s 3600) (str (js/Math.floor (/ s 60)) "m")
          (< s 86400) (str (js/Math.floor (/ s 3600)) "h")
          (< s (* 86400 7)) (str (js/Math.floor (/ s 86400)) "d"))))

(defn second-line
  "What the chat does while it works or waits on you, else the start of Claude's last reply:
  [kind text]."
  [{:keys [waiting running agents asks preview]}]
  (let [n (or agents 0)
        ask (first asks)]
    (cond
      waiting (cond (= "question" (:kind ask)) ["waiting" (str "Asks you: " (text/one-line (:text ask)))]
                    (seq preview) ["waiting" (text/one-line preview)]
                    :else ["waiting" "Waiting for you"])
      running ["working" (if (pos? n) (str "Claude working · " n " agent" (when (not= n 1) "s")) "Claude working")]
      (pos? n) ["agents" (str "Agents working · " n)]
      :else ["reply" (text/one-line preview)])))

(defn hits-label
  "A found chat's count of hits."
  [n]
  (str n " hit" (when (not= 1 n) "s")))

(defn empty-note
  "What an empty list says."
  [q archived?]
  (cond (seq q) "No match." archived? "Nothing archived." :else "No chats yet."))

(defn fresh-chat
  "The chat being started, before the list has it (README decision 31): the new chat on screen
  (nothing open), or one sent and on its way (`created` not back yet). {:title :color :server
  :folder}, or nil when the open chat is in the list or the caller only looks (read-only)."
  [{:keys [open pending-new servers new-settings new-server ro]}]
  (let [[s c] open]
    (cond
      ro nil
      (nil? open) {:title "New chat" :color (:color new-settings) :server new-server :folder (:folder new-settings)}
      (and (contains? pending-new c) (nil? (get-in servers [s :rows c])))
      {:title (or (not-empty (first (str/split-lines (str (get-in pending-new [c :text]))))) "New chat")
       :color (:color new-settings) :server s :folder (:folder new-settings)}
      :else nil)))
