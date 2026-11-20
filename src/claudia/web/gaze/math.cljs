(ns claudia.web.gaze.math
  "The head pointer's arithmetic (2026-11-20), all pure. The iPhone/iPad app (GazeTracker.swift)
  sends one ARKit reading per frame; this takes the head's place and turn from it and follows the
  line straight out of the face (where the nose points) to the plane the camera sits in, the
  screen's (metres, camera axes). A straight map from that point to the window (fractions 0 to 1
  of its width and height) is learned from five dots you point at, one per screen turn; the One
  Euro filter smooths it and taps correct its drift. Eyes were tried first: they jump and drift,
  the head moves steadily.

  A reading is a map {:yaw :pitch :x :y :z ...}: the face's forward axis as yaw and pitch
  (radians), its place (metres), in the camera's axes.")

;; ---- features ----

(defn- finite? [v] (and (number? v) (js/isFinite v)))

(defn head-hit
  "Where the line out of the face meets the plane z = 0 (the screen's), [x y] in metres; nil when
  a number is missing or the face points along the screen."
  [{:keys [yaw pitch x y z]}]
  (when (every? finite? [yaw pitch x y z])
    (let [c (js/Math.cos pitch)
          fx (* c (js/Math.sin yaw)) fy (js/Math.sin pitch) fz (* c (js/Math.cos yaw))]
      (when (> (js/Math.abs fz) 1e-3)
        (let [t (/ (- z) fz)] [(+ x (* t fx)) (+ y (* t fy))])))))

(defn features
  "The feature vector of one reading: where the head points on the screen's plane; nil without one."
  [reading]
  (head-hit reading))

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

(def margin "How far the corner dots sit from the window's edges (fraction)." 0.12)

(def dots
  "The dots you point at, in the order shown: the middle, then the four corners."
  (let [lo margin hi (- 1 margin)]
    [[0.5 0.5] [lo lo] [hi lo] [hi hi] [lo hi]]))

(def dot-ms "How long each dot stays." 1500)
(def settle-ms "The first part of a dot's time, while the head turns to it: no samples." 700)

(defn cal-moment
  "Where a calibration over order (dot indices) started at start stands at time t (ms): {:i dot
  index, :p progress through the dot (0 to 1), :sample? whether a reading now is taken}, or
  {:done? true}."
  [order start t]
  (let [el (- t start)
        k (js/Math.floor (/ el dot-ms))]
    (if (>= k (count order))
      {:done? true}
      (let [in (- el (* k dot-ms))]
        {:i (nth order k) :p (/ in dot-ms) :sample? (>= in settle-ms)}))))

;; ---- cleaning a calibration ----

(def outlier-min "A sample further than this (window fraction) from where the first fit puts it may be dropped." 0.06)
(def bad-dot "A dot whose samples land further than this on average is asked again (once)." 0.07)

(defn- miss [model {:keys [f at]}] (len (mapv - (predict model f) at)))

(defn- clean-pass
  "The samples a fit of them puts no further off than k times the median miss and than floor."
  [samples k floor]
  (if-let [model (fit samples)]
    (let [misses (sort (map #(miss model %) samples))
          cut (max floor (* k (nth misses (quot (count misses) 2))))]
      (filterv #(<= (miss model %) cut) samples))
    samples))

(defn cleaned
  "The samples without the ones a fit puts far off (a turn away, a face half seen): a loose pass
  first, so a far stray cannot bend the second fit, then a strict one."
  [samples]
  (let [loose (clean-pass samples 3 (* 2 outlier-min))]
    (clean-pass (if (>= (count loose) min-samples) loose samples) 2 outlier-min)))

(defn bad-dots
  "The dot indices whose samples a model misses by more than bad-dot on average, or that have
  fewer than 4 samples."
  [model samples]
  (let [by-dot (group-by :i samples)]
    (vec (for [i (range (count dots))
               :let [ss (by-dot i)]
               :when (or (< (count ss) 4)
                         (> (/ (reduce + (map #(miss model %) ss)) (count ss)) bad-dot))]
           i))))

;; ---- smoothing ----

(defn clamp01 "v kept within 0 and 1." [v] (max 0 (min 1 v)))

(defn- alpha [cutoff dt]
  (let [tau (/ 1 (* 2 js/Math.PI cutoff))] (/ 1 (+ 1 (/ tau dt)))))

(def one-euro-params
  "The pointer's filter: a still head gets a low cutoff (no shake), a moving one a high one (no lag)."
  {:min-cutoff 1.0 :beta 1.5 :d-cutoff 1.0})

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

;; ---- taps ----

(def tap-reach "A tap further than this from the raw point is not taken as where the head pointed." 0.3)
(def tap-pull "How much of the miss one tap corrects." 0.6)

(defn tapped
  "The state after a tap at q (window fractions): you point where you tap, so the miss between the
  raw point and q corrects the offset."
  [{:keys [raw offset] :as st} q]
  (if (and raw (< (len (mapv - q raw)) tap-reach))
    (let [off (or offset [0 0])
          gap (mapv - q (mapv + raw off))]
      (assoc st :offset (mapv + off (mapv #(* tap-pull %) gap))))
    st))

;; ---- one frame ----

(defn- finish
  "A calibration's end: cleaned samples fitted; bad dots asked again once; else tracking or failed."
  [{:keys [samples redone?] :as st} t]
  (let [good (cleaned samples)
        model (fit good)
        bad (when model (bad-dots model good))]
    (cond
      (nil? model) (assoc st :phase :failed :samples [] :show nil)
      (and (seq bad) (not redone?))
      (assoc st :order bad :cal-start t :redone? true :samples (filterv #(not (some #{(:i %)} bad)) good))
      :else (assoc st :phase :tracking :model model :error (error model good) :samples [] :show nil))))

(defn- calibrate-step
  "A calibration state after one reading's features f (nil: skipped) at t."
  [{:keys [cal-start order] :as st} f t]
  (let [{:keys [done? i p sample?]} (cal-moment order cal-start t)]
    (if done?
      (finish st t)
      (cond-> (assoc st :show {:kind :dot :at (dots i) :p p :again? (:redone? st)})
        (and sample? f) (update :samples (fnil conj []) {:f f :at (dots i) :i i})))))

(defn- track-step
  "A tracking state after one reading's features f at t (ms): the raw point (kept for taps), the
  offset added, smoothed; or the last pointer, :lost, without f."
  [{:keys [model offset fx fy] :as st} f t]
  (if-not f
    (assoc st :show (some-> (:show st) (assoc :lost? true)))
    (let [raw (predict model f)
          [x y] (mapv clamp01 (mapv + raw (or offset [0 0])))
          s (/ t 1000)
          nx (one-euro fx x s one-euro-params)
          ny (one-euro fy y s one-euro-params)]
      (assoc st :raw raw :fx nx :fy ny :show {:kind :pointer :at [(:x nx) (:x ny)]}))))

(defn step
  "The gaze state after one reading at time t (ms). State: {:phase :calibrating|:tracking|:failed,
  :cal-start, :order, :samples, :model, :raw, :offset, :fx, :fy, :show}; :show is what to draw now:
  {:kind :dot :at :p :again?} (a calibration dot), {:kind :pointer :at :lost?} or nil."
  [st reading t]
  (let [f (some-> reading features)]
    (case (:phase st)
      :calibrating (calibrate-step st f t)
      :tracking (track-step st f t)
      st)))

(defn calibrating
  "A fresh calibration starting at t (ms)."
  [t]
  {:phase :calibrating :cal-start t :order (vec (range (count dots))) :samples []})

(defn tracking
  "Tracking with a saved model."
  [model]
  {:phase :tracking :model model})
