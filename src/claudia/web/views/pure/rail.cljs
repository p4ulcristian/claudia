(ns claudia.web.views.pure.rail
  "The rail as plain data (README decision 80): its width and when its circles become cards, each
  server's short mark, and what a card and a group say."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.sidebar :as sidebar]
            [claudia.web.views.pure.usage :as usage]))

;; ---- the width ----

(def narrow "The rail of circles, px." 88)
(def card-widths "The widths the cards snap to, px." [260 340])
(def cards-from "From this width on (px) the rail shows cards." 170)
(def page-room "What the rail always leaves to the chat and the picture, px." 600)

(defn cards? "Whether a rail w px wide shows cards." [w] (>= w cards-from))

(defn max-width
  "The widest the rail may be in a window w px wide: it leaves page-room, never under narrow."
  [window-w]
  (max narrow (- window-w page-room)))

(defn dragged
  "The rail's width while its edge is at client-x (the rail starts at rail-left): from narrow to most."
  [rail-left client-x most]
  (js/Math.round (max narrow (min most (- client-x rail-left)))))

(defn snap
  "Where a width w settles: narrow below cards-from (or when no card width fits most), else the
  card width nearest to w among those that fit."
  [w most]
  (let [fits (filter #(<= % most) card-widths)]
    (if (or (not (cards? w)) (empty? fits))
      narrow
      (apply min-key #(js/Math.abs (- % w)) fits))))

(defn shown
  "The width a kept width (stored) shows at in a window w px wide."
  [stored window-w]
  (snap stored (max-width window-w)))

(defn parse-stored
  "A width kept on the device (a string from localStorage, or nil) as px; narrow when there is none."
  [s]
  (let [n (js/parseFloat s)]
    (if (and (js/isFinite n) (pos? n)) n narrow)))

;; ---- the servers' marks ----

(defn- words-of [label]
  (remove str/blank? (str/split (str/replace (str label) #"['’]s\b" "") #"[\s\-_.'’/]+")))

(defn- prefix [s n] (let [s (str/replace s #"[\s\-_.'’/]+" "")] (subs s 0 (min n (count s)))))

(defn- capital [s] (str (str/upper-case (subs s 0 (min 1 (count s)))) (str/lower-case (subs s (min 1 (count s))))))

(defn- candidates
  "A label's marks, best first: its first letter, its words' initials, then its first 2, 3, 4 letters."
  [label]
  (let [ws (words-of label)
        initials (str/upper-case (apply str (map first (take 3 ws))))]
    (->> (concat [(str/upper-case (prefix (str label) 1))]
                 (when (> (count ws) 1) [(subs initials 0 (min 2 (count initials))) initials])
                 (map #(capital (prefix (str label) %)) [2 3 4]))
         (remove str/blank?)
         distinct)))

(defn- others-first?
  "Whether another server (not the one whose own first mark is own) would rather have mark m."
  [firsts own m]
  (> (get firsts m 0) (if (= m own) 1 0)))

(defn server-marks
  "Each server's short mark ({:name :label} in order -> {name mark}), unique among them: a mark
  another server would rather have (its first) is skipped, and one already given is not given again."
  [servers]
  (let [cands (mapv #(candidates (or (not-empty (:label %)) (:name %))) servers)
        firsts (frequencies (keep first cands))
        pick (fn [taken i]
               (let [own (first (get cands i))]
                 (or (some #(when-not (or (taken %) (others-first? firsts own %)) %) (get cands i))
                     (str (or own "?") (inc i)))))]
    (first (reduce (fn [[marks taken] [i {n :name}]]
                     (let [m (pick taken i)] [(assoc marks n m) (conj taken m)]))
                   [{} #{}]
                   (map-indexed vector servers)))))

;; ---- what a card says ----

(defn card-state
  "A chat's state on its card: :waiting (a question waits on you), :running (its turn or its
  agents), :failed, :stopped, :done (its last turn ended), :new (no turn yet)."
  [{:keys [waiting running agents lastTurn ended]}]
  (cond waiting :waiting
        (or running (pos? (or agents 0))) :running
        (:failed lastTurn) :failed
        (:stopped lastTurn) :stopped
        (pos? (or ended 0)) :done
        :else :new))

(def state-words
  {:waiting "waiting for answer" :running "running" :failed "failed" :stopped "stopped" :done "done" :new "new"})

(defn card-when
  "The card's time next to its state: the clock and agents while running, else how long ago the
  question came, the turn ended or the chat was last active."
  [{:keys [askedAt ended at] :as r} state now]
  (let [ago #(when (pos? %) (sidebar/short-ago % now))]
    (case state
      :running (second (sidebar/status-line (dissoc r :waiting) false now))
      :waiting (some-> askedAt ago)
      (:failed :stopped :done) (ago (or ended 0))
      (ago (or at 0)))))

(defn folder-name
  "The last part of a folder's path (\"\" for none)."
  [folder]
  (or (last (remove str/blank? (str/split (str folder) #"/"))) ""))

(defn model-name
  "The model a chat runs with, as people say it: its name among the server's models, else the id
  made readable; the server's default model when the chat has none; nil when nothing is known."
  [models model default-model]
  (let [m (or (not-empty model) (not-empty default-model))]
    (when m (or (some #(when (= (:value %) m) (:name %)) models) (usage/pretty-model m)))))

(defn card-meta
  "The card's second line: the server (only with more than one), the project or folder, the model."
  [{:keys [server-label project folder model multi?]}]
  (str/join " · " (remove str/blank? [(when multi? server-label) (or (not-empty project) (folder-name folder)) model])))

;; ---- a group ----

(defn group-state
  "A group's state, its most pressing chat's: waiting over running over failed over done, else :new."
  [rows]
  (let [states (set (map card-state rows))]
    (or (some states [:waiting :running :failed :done :stopped]) :new)))

(defn group-status
  "A group's line under its name on the narrow rail, [kind text]: how many ask, else how many run,
  else how many chats."
  [rows]
  (let [n-of #(count (filter (comp #{%} card-state) rows))
        asks (n-of :waiting) runs (n-of :running)]
    (cond (pos? asks) ["asks" (str "? " asks " ask" (when (> asks 1) "s"))]
          (pos? runs) ["running" (str runs " running")]
          :else ["quiet" (str (count rows) " chat" (when (not= 1 (count rows)) "s"))])))
