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
