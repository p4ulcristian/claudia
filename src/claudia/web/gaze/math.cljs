(ns claudia.web.gaze.math
  "The gaze pointer's arithmetic (2026-11-20), all pure. The iPhone/iPad app (GazeTracker.swift)
  sends one reading per frame: where the eyes' ray meets the screen's plane (metres, camera axes)
  and the head's place and turn. A ridge regression from readings to a point in the window
  (fractions 0 to 1 of its width and height) is learned from the calibration dots, one model per
  screen turn; the One Euro filter steadies the pointer.

  A reading is a map {:hx :hy :yaw :pitch :x :y :z :blink}, numbers; :blink 0 (open) to 1 (shut).")

;; ---- features ----

(def blink-max "Above this (either eye's blink, 0 to 1) a reading is skipped: the eyes are shut." 0.5)

(defn features
  "The feature vector of one reading: the ray's hit, its squares and product, and the head; nil
  while blinking or when a number is missing."
  [{:keys [hx hy yaw pitch x y z blink]}]
  (let [nums [hx hy yaw pitch x y z]]
    (when (and (every? #(and (number? %) (js/isFinite %)) nums)
               (< (or blink 0) blink-max))
      [hx hy (* hx hx) (* hy hy) (* hx hy) yaw pitch x y z])))

;; ---- linear algebra ----

(defn- pivot
  "Matrix m (rows of n+1) with the row of the largest entry in column col (from col down) swapped
  into place col; nil when that column is all zeros (singular)."
  [m col]
  (let [n (count m)
        p (apply max-key #(js/Math.abs (get-in m [% col])) (range col n))]
    (when (> (js/Math.abs (get-in m [p col])) 1e-12)
      (assoc m col (m p) p (m col)))))

(defn- eliminate
  "Matrix m with column col cleared in every row but col, whose entry is made 1."
  [m col]
  (let [prow (let [r (m col) pv (r col)] (mapv #(/ % pv) r))]
    (mapv (fn [i row]
            (if (= i col) prow (let [f (row col)] (mapv - row (mapv #(* f %) prow)))))
          (range) m)))

(defn solve
  "x with A x = b (A square, as a vector of rows), by Gauss-Jordan with partial pivoting; nil when A
  is singular."
  [a b]
  (some->> (range (count b))
           (reduce (fn [m col] (if-let [p (pivot m col)] (eliminate p col) (reduced nil)))
                   (mapv (fn [row bi] (conj (vec row) bi)) a b))
           (mapv peek)))

(defn- len [[ax ay]] (js/Math.sqrt (+ (* ax ax) (* ay ay))))

(defn- stats-of
  "Mean and spread of every feature, a spread of 0 taken as 1 (a feature that never moved)."
  [rows]
  (let [n (count rows)]
    (mapv (fn [j]
            (let [c (mapv #(nth % j) rows)
                  mean (/ (reduce + c) n)
                  sd (js/Math.sqrt (/ (reduce + (map #(let [d (- % mean)] (* d d)) c)) n))]
              [mean (if (< sd 1e-9) 1 sd)]))
          (range (count (first rows))))))

(defn- scaled [st row] (into [1] (map (fn [v [mean sd]] (/ (- v mean) sd)) row st)))

(defn ridge
  "Weights w minimising |X w - y|^2 + lambda |w|^2 (the first weight, the bias, not penalised)."
  [xs ys lambda]
  (let [k (count (first xs))
        xtx (vec (for [i (range k)]
                   (vec (for [j (range k)]
                          (+ (reduce + (map #(* (nth % i) (nth % j)) xs))
                             (if (and (= i j) (pos? i)) lambda 0))))))
        xty (vec (for [i (range k)] (reduce + (map #(* (nth %1 i) %2) xs ys))))]
    (solve xtx xty)))

(def lambda "How hard the regression is pulled towards simple answers (features scaled to spread 1)." 0.5)

(def min-samples "Fewer calibration samples than this make no model." 20)

(defn fit
  "The model from calibration samples [{:f features :at [x y]}]: the features' scaling and one
  weight vector per window axis; nil with too few samples or when the fit fails."
  [samples]
  (when (>= (count samples) min-samples)
    (let [st (stats-of (map :f samples))
          xs (mapv #(scaled st (:f %)) samples)
          wx (ridge xs (mapv #(first (:at %)) samples) lambda)
          wy (ridge xs (mapv #(second (:at %)) samples) lambda)]
      (when (and wx wy) {:stats st :wx wx :wy wy}))))

(defn predict
  "The point [x y] (fractions of the window) a model gives for features."
  [{st :stats :keys [wx wy]} f]
  (let [row (scaled st f)]
    [(reduce + (map * wx row)) (reduce + (map * wy row))]))

(defn error
  "The mean distance (fractions of the window) between where the model puts the samples and where
  they were looked at."
  [model samples]
  (when (seq samples)
    (/ (reduce + (map (fn [{:keys [f at]}] (len (mapv - (predict model f) at))) samples))
       (count samples))))

;; ---- calibration ----

(def margin "How far the outer calibration dots sit from the window's edges (fraction)." 0.08)

(def dots
  "The calibration dots, in the order shown: the middle, the corners, then the edges' middles."
  (let [lo margin hi (- 1 margin)]
    [[0.5 0.5] [lo lo] [hi lo] [hi hi] [lo hi] [0.5 lo] [hi 0.5] [0.5 hi] [lo 0.5]]))

(def dot-ms "How long each calibration dot stays." 1600)
(def settle-ms "The first part of a dot's time, while the eyes travel to it: no samples." 600)

(defn cal-moment
  "Where a calibration started at start stands at time t (ms): {:i dot index, :p progress through
  the dot (0 to 1), :sample? whether a reading now is taken}, or {:done? true}."
  [start t]
  (let [el (- t start)
        i (js/Math.floor (/ el dot-ms))]
    (if (>= i (count dots))
      {:done? true}
      (let [in (- el (* i dot-ms))]
        {:i i :p (/ in dot-ms) :sample? (>= in settle-ms)}))))

;; ---- smoothing ----

(defn- alpha [cutoff dt]
  (let [tau (/ 1 (* 2 js/Math.PI cutoff))] (/ 1 (+ 1 (/ tau dt)))))

(def one-euro-params
  "The pointer's filter: slow eyes get a low cutoff (steady), fast moves a high one (little lag)."
  {:min-cutoff 0.8 :beta 2.0 :d-cutoff 1.0})

(defn one-euro
  "One step of the One Euro filter (Casiez et al. 2012) for a value v at time t (seconds): the new
  state {:x :dx :t}; its :x is the smoothed value. state nil starts it."
  [state v t {:keys [min-cutoff beta d-cutoff]}]
  (if-not state
    {:x v :dx 0 :t t}
    (let [dt (max 1e-3 (- t (:t state)))
          a-d (alpha d-cutoff dt)
          edx (+ (* a-d (/ (- v (:x state)) dt)) (* (- 1 a-d) (:dx state)))
          a (alpha (+ min-cutoff (* beta (js/Math.abs edx))) dt)]
      {:x (+ (* a v) (* (- 1 a) (:x state))) :dx edx :t t})))

(defn clamp01 "v kept within 0 and 1." [v] (max 0 (min 1 v)))

;; ---- one frame ----

(defn- calibrate-step
  "A calibration state after one reading's features f (nil: skipped) at t: samples gathered, and at
  the end the fitted model (:phase :tracking) or :phase :failed."
  [{:keys [cal-start samples] :as st} f t]
  (let [{:keys [done? i p sample?]} (cal-moment cal-start t)]
    (if done?
      (if-let [model (fit samples)]
        (assoc st :phase :tracking :model model :error (error model samples) :samples [] :show nil)
        (assoc st :phase :failed :samples [] :show nil))
      (cond-> (assoc st :show {:kind :dot :at (dots i) :p p})
        (and sample? f) (update :samples (fnil conj []) {:f f :at (dots i)})))))

(defn- track-step
  "A tracking state after one reading's features f at t: the smoothed pointer, or :lost without f."
  [{:keys [model fx fy] :as st} f t]
  (if-not f
    (assoc st :show (some-> (:show st) (assoc :lost? true)))
    (let [[x y] (predict model f)
          s (/ t 1000)
          nx (one-euro fx (clamp01 x) s one-euro-params)
          ny (one-euro fy (clamp01 y) s one-euro-params)]
      (assoc st :fx nx :fy ny :show {:kind :pointer :at [(:x nx) (:y ny)]}))))

(defn step
  "The gaze state after one reading at time t (ms). State: {:phase :calibrating|:tracking|:failed,
  :cal-start, :samples, :model, :fx, :fy, :show}; :show is what to draw now: {:kind :dot :at :p}
  (a calibration dot), {:kind :pointer :at :lost?} or nil."
  [st reading t]
  (let [f (some-> reading features)]
    (case (:phase st)
      :calibrating (calibrate-step st f t)
      :tracking (track-step st f t)
      st)))

(defn calibrating
  "A fresh calibration starting at t (ms)."
  [t]
  {:phase :calibrating :cal-start t :samples []})

(defn tracking
  "Tracking with a saved model."
  [model]
  {:phase :tracking :model model})
