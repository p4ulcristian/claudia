(ns claudia.web.views.mic-flower
  "The mic's flower (README decision 20): while the mic is held, a 240 px canvas round the button,
  big enough to show past the finger on it. In the middle a disc, amber while the microphone wakes
  and red once it records, with the time spoken so far and what letting go would do; round it the
  48 bars that follow the voice. Four petals open outside the bars: left sends, up cancels, right
  adds the words to the message, down sends now (the running turn stops first); letting go in place
  sends too. The petal the finger is over lights. On the tablet's rail (README decision 73) the
  flower is half: its petals and bars only to the left of the mic, top to bottom cancel, add, send,
  now; and while the mic is dragged past the petals the disc alone shows, blue, saying move."
  (:require [claudia.web.views.pure.mic :as pure]))

(def size "The canvas, CSS px (square, centred on the button)." 240)

;; Radii, CSS px from the middle: the disc, the bars (from bar-r, up to bar-len long), the petals.
(def ^:private disc-r 36)
(def ^:private bar-r 39)
(def ^:private bar-len 24)
(def ^:private petal-in 66)
(def ^:private petal-out 116)

(def ^:private petals
  ;; [way from-degrees to-degrees icon word colour-var]; 0 degrees = right, -90 = up.
  [[:send 145 215 "➤" "send" "--green"]
   [:cancel -135 -45 "✕" "cancel" "--red"]
   [:add -35 35 "+" "add" "--blue"]
   [:now 55 125 :now "now" "--amber"]])

(def ^:private half-petals
  ;; The half flower's: the left half in four, 4 degrees apart.
  [[:cancel -133 -92 "✕" "cancel" "--red"]
   [:add -178 -137 "+" "add" "--blue"]
   [:send 137 178 "➤" "send" "--green"]
   [:now 92 133 :now "now" "--amber"]])

(def ^:private words {:send "send" :add "add" :cancel "cancel" :now "now"})

(defn- css [^js el v] (.trim (.getPropertyValue (js/getComputedStyle el) v)))

(defn- rad [d] (* d (/ js/Math.PI 180)))

(defn- petal-path! [^js g r1 r2 a0 a1]
  (.beginPath g)
  (.arc g 0 0 r2 (rad a0) (rad a1))
  (.arc g 0 0 r1 (rad a1) (rad a0) true)
  (.closePath g))

(defn- now-icon!
  "Send now, drawn: two chevrons running into a bar (go straight to the end), centred on x y."
  [^js g colour x y]
  (.save g)
  (set! (.-strokeStyle g) colour)
  (set! (.-lineWidth g) 2.2)
  (set! (.-lineCap g) "round")
  (set! (.-lineJoin g) "round")
  (.beginPath g)
  (doseq [dx [-8 -1]]
    (.moveTo g (+ x dx) (- y 6))
    (.lineTo g (+ x dx 6) y)
    (.lineTo g (+ x dx) (+ y 6)))
  (.moveTo g (+ x 8) (- y 6))
  (.lineTo g (+ x 8) (+ y 6))
  (.stroke g)
  (.restore g))

(defn- petal! [^js g ^js el way [p a0 a1 icon word colour]]
  (let [on (= way p) c (css el colour) mid (rad (/ (+ a0 a1) 2)) r (/ (+ petal-in petal-out) 2)
        x (* r (js/Math.cos mid)) y (* r (js/Math.sin mid))]
    (petal-path! g petal-in petal-out a0 a1)
    ;; Opaque underneath: what lies behind (the rail on a tablet) never shows through a petal.
    (set! (.-fillStyle g) (css el "--bg2"))
    (.fill g)
    (set! (.-globalAlpha g) (if on 0.92 0.16))
    (set! (.-fillStyle g) c)
    (.fill g)
    (set! (.-globalAlpha g) 1)
    (set! (.-fillStyle g) (if on (css el "--bg2") c))
    (set! (.-textAlign g) "center")
    (if (keyword? icon)
      (now-icon! g (if on (css el "--bg2") c) x (- y 9))
      (do (set! (.-font g) (str "700 19px " (css el "--sans")))
          (.fillText g icon x (- y 3))))
    (set! (.-font g) (str "600 12px " (css el "--sans")))
    (.fillText g word x (+ y 14))))

(defn- ground!
  "The round inside the petals, opaque: the composer under the bars stays hidden."
  [^js g ^js el]
  (.beginPath g)
  (.arc g 0 0 petal-in 0 (* 2 js/Math.PI))
  (set! (.-fillStyle g) (css el "--bg2"))
  (.fill g))

(defn- left-half?
  "Whether bar i of n points into the left half (90 to 270 degrees)."
  [i n]
  (<= (/ n 4) i (* 3 (/ n 4))))

(defn- bars!
  "The voice's bars round the disc (levels 0-1, one per bar, the first pointing right); half?: only
  the left half's."
  [^js g ^js el levels half?]
  (set! (.-fillStyle g) (css el "--red"))
  (doseq [[i level] (map-indexed vector levels)
          :when (and (> level 0.02) (or (not half?) (left-half? i (count levels))))]
    (.save g)
    (.rotate g (* 2 js/Math.PI (/ i pure/bar-count)))
    (.fillRect g bar-r -1.5 (+ 3 (* level bar-len)) 3)
    (.restore g)))

(defn- disc-text
  "The disc's two lines: above the mic icon, under it."
  [{:keys [phase secs way]}]
  (case phase
    :recording [(pure/clock-text (* 1000 secs)) (get words way)]
    :moving ["" "move"]
    ["…" "waking"]))

(defn- disc! [^js g ^js el {:keys [phase] :as state}]
  (let [[above under] (disc-text state)]
    (.beginPath g)
    (.arc g 0 0 disc-r 0 (* 2 js/Math.PI))
    (set! (.-fillStyle g) (css el (case phase :recording "--red" :moving "--blue" "--amber")))
    (.fill g)
    (set! (.-fillStyle g) "#fff")
    (set! (.-textAlign g) "center")
    ;; The mic icon sits in the middle (-13..13, 30 px on the iPad's edge): the clock above it,
    ;; the word below, both the same distance from it.
    (set! (.-textBaseline g) "middle")
    (set! (.-font g) (str "700 11px " (css el "--mono")))
    (.fillText g above 0 -23)
    (set! (.-font g) (str "600 10px " (css el "--sans")))
    (.fillText g under 0 23)
    (set! (.-textBaseline g) "alphabetic")))

(defn- ground-half!
  "The half flower's ground: the left half round inside the petals."
  [^js g ^js el]
  (.beginPath g)
  (.arc g 0 0 petal-in (/ js/Math.PI 2) (* 1.5 js/Math.PI))
  (.closePath g)
  (set! (.-fillStyle g) (css el "--bg2"))
  (.fill g))

(defn- flower!
  "The petals, their ground and the bars: whole, or half (only to the left)."
  [^js g ^js canvas {:keys [half? way levels]}]
  (doseq [p (if half? half-petals petals)] (petal! g canvas way p))
  (if half? (ground-half! g canvas) (ground! g canvas))
  (bars! g canvas levels half?))

(defn draw!
  "One frame: the petals, the ground inside them, the bars, the disc (while moving: the disc
  alone). state: {:phase (:starting :recording :moving) :levels (one 0-1 per bar) :secs :way
  :half?}."
  [^js canvas state]
  (let [g (.getContext canvas "2d") dpr (or js/devicePixelRatio 1)]
    (.setTransform g 1 0 0 1 0 0)
    (.clearRect g 0 0 (.-width canvas) (.-height canvas))
    (.setTransform g dpr 0 0 dpr 0 0)
    (.translate g (/ size 2) (/ size 2))
    (when-not (= :moving (:phase state)) (flower! g canvas state))
    (disc! g canvas state)))

(defn wipe! [^js canvas]
  (let [g (.getContext canvas "2d")]
    (.setTransform g 1 0 0 1 0 0)
    (.clearRect g 0 0 (.-width canvas) (.-height canvas))))
