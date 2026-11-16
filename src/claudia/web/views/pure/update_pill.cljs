(ns claudia.web.views.pure.update-pill
  "The update pill, as data (README decision 32): from what GET /hub/progress says, whether the pill
  shows, what it says, and the steps its panel lists.")

(def ^:private settled-shown-ms
  "A finished update stays on the pill this long, then the pill goes."
  90000)

(def ^:private build-shown-ms
  "A build (no merge yet) shows this long after the builder last said something."
  600000)

(defn- waits? [x] (= "waiting" (:state x)))

(defn- parts [release]
  (cons (assoc (:hub release) :name "hub") (for [[k v] (:machines release)] (assoc v :name (name k)))))

(defn- secs-text [s] (str (js/Math.round s) " s"))

(defn clock-text
  "ms as a running clock: \"0:07\", \"1:42\", \"1:02:05\" (never below 0)."
  [ms]
  (let [s (js/Math.floor (/ (max 0 ms) 1000))
        two #(if (< % 10) (str "0" %) (str %))
        h (quot s 3600) m (quot (mod s 3600) 60)]
    (if (pos? h) (str h ":" (two m) ":" (two (mod s 60))) (str m ":" (two (mod s 60))))))

(defn- with-clock
  "label with the time since start, while it runs: \"Updating 3/5 · 1:10\"."
  [label start t]
  (if (number? start) (str label " \u00b7 " (clock-text (- t start))) label))

(defn release-label
  "What the pill says about a release at t: Updating d/n and the time since the merge while it
  moves, then how long it took."
  [release t]
  (let [ps (remove #(= "asleep" (:state %)) (parts release))
        done (count (filter #(= "done" (:state %)) ps))
        missing (count (filter #(= "not reached" (:state %)) ps))]
    (cond
      (some waits? ps) {:label (with-clock (str "Updating " done "/" (count ps)) (:merged release) t) :tone "running"}
      (pos? missing) {:label (str missing " not reached") :tone "warn"}
      :else {:label (str "Live in " (secs-text (apply max 0 (keep :secs ps)))) :tone "done"})))

(defn- recent-build
  "The newest build the builder spoke of lately, unless it is the release's own (already merged)."
  [{:keys [builds release]} t]
  (let [b (first builds)]
    (when (and b (< (- t (:at b)) build-shown-ms)
               (not (and release (= (:tree b) (:tree release)) (>= (:merged release) (:started b)))))
      b)))

(defn pill
  "The pill: {:label :tone}, or nil when nothing is moving or just finished."
  [progress t]
  (let [r (:release progress)
        b (recent-build progress t)
        moving? (and r (some waits? (parts r)))
        fresh? (and r (< (- t (:merged r)) settled-shown-ms))]
    (cond
      (or moving? (and fresh? (not b))) (release-label r t)
      (= "pending" (:state b)) {:label (with-clock (str "Building " (subs (:commit b) 0 7)) (:started b) t)
                                :tone "running"}
      (= "failure" (:state b)) {:label "Build failed" :tone "bad"}
      fresh? (release-label r t)
      :else nil)))

(defn steps
  "The panel's lines: [what state note], the build first, then each machine and the hub."
  [{:keys [builds release]}]
  (let [b (first builds)]
    (vec (concat
          (when b [["builder" (:state b) (:note b)]])
          (when release [["merged" "done" (:title release)]])
          (for [p (parts release) :when release]
            [(:name p) (:state p) (when (:secs p) (secs-text (:secs p)))])))))

(defn updated-text
  "The small line under the gear: the version on screen (its commit) and when it went live, as
  \"8495c52 · 5 min ago\"; nil when unknown."
  [commit at t]
  (when (number? at)
    (let [s (max 0 (/ (- t at) 1000))
          n #(js/Math.floor (/ s %))]
      (str (when (seq commit) (str commit " \u00b7 "))
           (cond (< s 60) "just now"
                 (< s 3600) (str (n 60) " min ago")
                 (< s 86400) (str (n 3600) " h ago")
                 :else (str (n 86400) (if (= 1 (n 86400)) " day ago" " days ago")))))))
