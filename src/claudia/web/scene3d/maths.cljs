(ns claudia.web.scene3d.maths
  "The scene3d's numbers, without three.js: points as [x y z], easing curves, how far the camera
  stands, which way a wheel moves the view, and where an arrow's pieces go. The engine and the
  person turn these into three.js objects.")

(def deg (/ js/Math.PI 180))
(def up [0 1 0])

;; ---- points ----

(defn v+ [[a b c] [x y z]] [(+ a x) (+ b y) (+ c z)])
(defn v- [[a b c] [x y z]] [(- a x) (- b y) (- c z)])
(defn v* [[x y z] s] [(* x s) (* y s) (* z s)])
(defn vlen [[x y z]] (js/Math.sqrt (+ (* x x) (* y y) (* z z))))
(defn vdist [a b] (vlen (v- a b)))

(defn vnorm
  "Length 1 the same way (as three.js does it: a zero vector stays zero)."
  [v]
  (v* v (/ 1 (let [l (vlen v)] (if (zero? l) 1 l)))))

(defn lerp3 [[a b c] [x y z] t] [(+ a (* t (- x a))) (+ b (* t (- y b))) (+ c (* t (- z c)))])

;; ---- easing (p from 0 to 1) ----

(defn ease-out-cubic [p] (- 1 (js/Math.pow (- 1 p) 3)))
(defn ease-in-out-quad [p] (if (< p 0.5) (* 2 p p) (- 1 (/ (js/Math.pow (+ (* -2 p) 2) 2) 2))))
(defn ease-in-out-cubic [p] (if (< p 0.5) (* 4 p p p) (- 1 (/ (js/Math.pow (+ (* -2 p) 2) 3) 2))))

(defn progress
  "How far (0..1) a move that started at `at` and lasts `dur` is at `now`."
  [now at dur]
  (min 1 (max 0 (/ (- now at) dur))))

;; ---- the camera ----

(defn fit-distance
  "How far the camera stands so a ball of radius r fills the view (fov in degrees)."
  [r fov]
  (* 0.95 (/ r (js/Math.sin (/ (* fov deg) 2)))))

(defn orbit-dir
  "The direction from the target to the camera for an azimuth and elevation in degrees."
  [az el]
  (let [a (* az deg) e (* el deg)]
    [(* (js/Math.sin a) (js/Math.cos e)) (js/Math.sin e) (* (js/Math.cos a) (js/Math.cos e))]))

(defn sun-frame
  "The sun's shadow camera for a scene of radius r."
  [r]
  {:left (* r -1.15) :right (* r 1.15) :top (* r 1.15) :bottom (* r -1.15) :near 0.1 :far (* r 5)})

(defn advance-clock
  "The script's clock after a frame: real seconds since the last frame (at most 0.1) times the rate;
  no step on the first frame (last = 0)."
  [t previous now rate]
  (+ t (if (pos? previous) (* (min (/ (- now previous) 1000) 0.1) rate) 0)))

(defn focus-pulse "The focus glow's beat, 0..1, at wall time ms." [wall]
  (+ 0.5 (* 0.5 (js/Math.sin (/ wall 260)))))

(defn dim-step
  "One frame of a part fading back or forth towards `to`: the new dim, or nil when it is there."
  [dim to]
  (cond (> (js/Math.abs (- dim to)) 0.01) (+ dim (* 0.2 (- to dim)))
        (not= dim to) to
        :else nil))

;; ---- input ----

(defn wheel-kind
  "What a wheel event does: :pinch (a trackpad pinch, ctrl held) zooms finely, :mouse (a wheel's
  whole notches) zooms by steps, :pan (two fingers sliding) moves the view."
  [{:keys [mode dx dy ctrl]}]
  (cond ctrl :pinch
        (or (not= 0 mode) (and (zero? dx) (js/Number.isInteger dy) (>= (js/Math.abs dy) 50))) :mouse
        :else :pan))

(defn zoom-factor [kind dy]
  (if (= kind :pinch) (js/Math.exp (* 0.012 dy)) (js/Math.exp (* 0.1 (js/Math.sign dy)))))

(defn dolly-distance "The camera's new distance from the target, kept within 0.5..80." [factor dist]
  (min 80 (max 0.5 (* factor dist))))

(defn pan-per-pixel
  "Scene units per screen pixel at the target's distance (fov in degrees, h the stage height)."
  [dist fov h]
  (/ (* 2 dist (js/Math.tan (/ (* fov deg) 2))) (max h 1)))

(defn click?
  "A press and release in place (under 5 px) with the main button; a drag to turn is not a click."
  [[x0 y0] x y button]
  (and (some? x0) (zero? button) (< (js/Math.hypot (- x x0) (- y y0)) 5)))

;; Thin parts are hard to hit: if the exact click finds nothing, these offsets (px) are tried.
(def pick-offsets [[0 0] [6 0] [-6 0] [0 6] [0 -6] [10 10] [-10 10] [10 -10] [-10 -10]])

(defn ndc
  "A screen point as the camera's -1..1 coordinates within the rect {:left :top :width :height}."
  [x y {:keys [left top width height]}]
  [(- (* 2 (/ (- x left) width)) 1) (+ (* -2 (/ (- y top) height)) 1)])

;; ---- arrows ----

(defn path-length [pts] (reduce + 0 (map vdist pts (rest pts))))

(defn along
  "Where a point s along a polyline is (:p), and which way it runs there (:d)."
  [pts s]
  (loop [i 0 left s]
    (if (>= i (dec (count pts)))
      {:p (first pts) :d up}
      (let [a (nth pts i)
            seg (v- (nth pts (inc i)) a)
            len (vlen seg)]
        (if (or (<= left len) (= i (- (count pts) 2)))
          {:p (v+ a (v* (vnorm seg) (min left len))) :d seg}
          (recur (inc i) (- left len)))))))

(defn arrow-head "The arrow's head length for a path this long." [total] (min 0.4 (* total 0.35)))

(defn arrow-segments
  "Each straight piece of an arrow as {:pos :len :dir}: a cylinder centred at pos, len long along
  dir. The last stops where the head begins."
  [pts head]
  (let [n (dec (count pts))]
    (vec (for [i (range n)]
           (let [a (nth pts i)
                 d (v- (nth pts (inc i)) a)
                 len (vlen d)
                 dir (vnorm d)
                 stop (if (= i (dec n)) (max (- len head) 0.001) len)]
             {:pos (v+ a (v* dir (/ stop 2))) :len stop :dir dir})))))

(defn arrow-tip "The head: a cone of size head, its point on the path's end." [pts head]
  (let [end (peek pts)
        dir (vnorm (v- end (nth pts (- (count pts) 2))))]
    {:pos (v+ end (v* dir (/ head -2))) :dir dir}))

(defn dash-at
  "Dash i of n of a flowing arrow at `seconds`: {:pos :dir}. Dashes run 0.3 of the path a second."
  [pts total head i n seconds]
  (let [{:keys [p d]} (along pts (* (mod (+ (/ i n) (* seconds 0.3)) 1) (- total head)))]
    {:pos p :dir (vnorm d)}))

;; ---- two-bone chains (arms, legs) ----

(defn chain-reach
  "How far a two-bone chain (bones l1, l2) stretches towards a target len away: never quite
  straight, never folded tighter than its bones allow."
  [l1 l2 len]
  (min (max len (+ (js/Math.abs (- l1 l2)) 1e-3)) (* 0.9995 (+ l1 l2))))

(defn bend-angle
  "The angle at the chain's root between the line to the target (dist away) and its first bone."
  [l1 l2 dist]
  (js/Math.acos (min 1 (max -1 (/ (- (+ (* l1 l1) (* dist dist)) (* l2 l2)) (* 2 l1 dist))))))
