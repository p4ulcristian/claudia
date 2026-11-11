(ns claudia.web.world.screen-text
  "What a robot's screen says, as plain data: the body (the open question while it waits, else the
  start of the last reply), the status line and its colour, words wrapped into lines by a measure
  function (the canvas's, in claudia.web.world.screens), and which atlas slot each screen has.")

(defn ago
  "How long ago, short: now, 5m, 3h, 2d."
  [t now]
  (let [s (/ (- now t) 1000)]
    (cond (< s 60) "now"
          (< s 3600) (str (js/Math.floor (/ s 60)) "m")
          (< s 86400) (str (js/Math.floor (/ s 3600)) "h")
          :else (str (js/Math.floor (/ s 86400)) "d"))))

(defn status
  "The screen's last line: [text colour]."
  [{:keys [state at]} now]
  (case state
    :working ["● working…" "#8ff3ff"]
    :waiting ["? waiting on you" "#ffd36b"]
    :done ["✓ done · click to read" "#8dffb0"]
    :off ["offline" "#8b929c"]
    [(str "idle" (when at (str " · " (ago at now)))) "#aeb6c4"]))

(defn body
  "The screen's text: the open question while waiting, else the start of the last reply."
  [{:keys [state ask preview]}]
  (let [s (if (and (= :waiting state) ask) ask preview)]
    (if (seq s) s "No reply yet.")))

(defn- fits? [measure width line] (<= (measure line) width))

(defn- add-word
  "lines with word added to the last line, or on a new one when it does not fit."
  [measure width lines word]
  (let [last-line (peek lines)
        joined (if (seq last-line) (str last-line " " word) word)]
    (if (or (empty? lines) (fits? measure width joined))
      (conj (if (seq lines) (pop lines) lines) joined)
      (conj lines word))))

(defn- ellipsis
  "line cut so that it and an ellipsis fit width."
  [measure width line]
  (loop [l line]
    (if (or (empty? l) (fits? measure width (str l "…"))) (str l "…") (recur (subs l 0 (dec (count l)))))))

(defn wrap
  "text as at most max-lines lines of at most width (by measure); the last one cut with an ellipsis
  when text goes on."
  [measure width max-lines text]
  (let [words (remove empty? (.split (str text) #"\s+"))
        lines (reduce #(add-word measure width %1 %2) [] words)]
    (if (> (count lines) max-lines)
      (conj (vec (take (dec max-lines) lines)) (ellipsis measure width (nth lines (dec max-lines))))
      (mapv #(if (fits? measure width %) % (ellipsis measure width %)) lines))))

(defn content-key
  "What the screen shows, to redraw only when it changes (idle times move on the minute)."
  [r now]
  [(:title r) (:project r) (:color r) (:state r) (body r) (first (status r now))])

(defn slots
  "Atlas slots for robot ids (in order): kept ones stay where they are, new ones take the lowest free
  slots; ids past capacity get none. old and the answer: id -> slot."
  [old ids capacity]
  (let [kept (select-keys old ids)
        free (remove (set (vals kept)) (range capacity))
        fresh (remove #(contains? kept %) ids)]
    (merge kept (zipmap fresh free))))

(defn far-scale
  "How much bigger a screen is drawn at distance d from the camera, so far ones stay readable."
  [d]
  (max 1 (min 1.4 (/ d 45))))
