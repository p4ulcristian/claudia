(ns claudia.web.views.mic-flower
  "The mic's flower (README decision 20): while the mic is held, a 320 px canvas round the button,
  big enough to show past the finger on it. In the middle a disc, amber while the microphone wakes
  and red once it records, breathing with the voice, with the time spoken so far and what letting
  go would do. Two petals open where a thumb at the composer's left edge can go: up cancels, right
  adds the words to the message; letting go in place sends. The petal the finger is over lights."
  (:require [claudia.web.views.pure.mic :as pure]
            [claudia.web.views.pure.update-pill :as clock]))

(def size "The canvas, CSS px (square, centred on the button)." 320)

(def ^:private petals
  ;; [way from-degrees to-degrees icon word colour-var]; 0 degrees = right, -90 = up.
  [[:cancel -150 -40 "✕" "cancel" "--red"]
   [:add -30 40 "+" "add" "--blue"]])

(def ^:private words {:send "send" :add "add" :cancel "cancel"})

(defn- css [^js el v] (.trim (.getPropertyValue (js/getComputedStyle el) v)))

(defn- rad [d] (* d (/ js/Math.PI 180)))

(defn- petal-path! [^js g r1 r2 a0 a1]
  (.beginPath g)
  (.arc g 0 0 r2 (rad a0) (rad a1))
  (.arc g 0 0 r1 (rad a1) (rad a0) true)
  (.closePath g))

(defn- petal! [^js g ^js el way [p a0 a1 icon word colour]]
  (let [on (= way p) c (css el colour) mid (rad (/ (+ a0 a1) 2)) x (* 104 (js/Math.cos mid)) y (* 104 (js/Math.sin mid))]
    (petal-path! g 56 150 a0 a1)
    (set! (.-globalAlpha g) (if on 0.92 0.16))
    (set! (.-fillStyle g) c)
    (.fill g)
    (set! (.-globalAlpha g) 1)
    (set! (.-fillStyle g) (if on (css el "--bg2") c))
    (set! (.-textAlign g) "center")
    (set! (.-font g) (str "700 26px " (css el "--sans")))
    (.fillText g icon x (- y 2))
    (set! (.-font g) (str "600 15px " (css el "--sans")))
    (.fillText g word x (+ y 20))))

(defn- disc! [^js g ^js el {:keys [phase level secs way]}]
  (let [warm? (= phase :recording) r (+ 44 (* 10 level))]
    (.beginPath g)
    (.arc g 0 0 r 0 (* 2 js/Math.PI))
    (set! (.-fillStyle g) (css el (if warm? "--red" "--amber")))
    (.fill g)
    (set! (.-fillStyle g) "#fff")
    (set! (.-textAlign g) "center")
    (set! (.-font g) (str "700 19px " (css el "--mono")))
    (.fillText g (if warm? (clock/clock-text (* 1000 secs)) "…") 0 -10)
    (set! (.-font g) (str "600 12px " (css el "--sans")))
    (.fillText g (if warm? (get words way) "waking") 0 34)))

(defn draw!
  "One frame: the petals, then the disc. state: {:phase :level (0-1) :secs :way}."
  [^js canvas state]
  (let [g (.getContext canvas "2d") dpr (or js/devicePixelRatio 1)]
    (.setTransform g 1 0 0 1 0 0)
    (.clearRect g 0 0 (.-width canvas) (.-height canvas))
    (.setTransform g dpr 0 0 dpr 0 0)
    (.translate g (/ size 2) (/ size 2))
    (doseq [p petals] (petal! g canvas (:way state) p))
    (disc! g canvas state)))

(defn wipe! [^js canvas]
  (let [g (.getContext canvas "2d")]
    (.setTransform g 1 0 0 1 0 0)
    (.clearRect g 0 0 (.-width canvas) (.-height canvas))))

(defn level
  "How loud the voice is now (0-1) from the analyser's spectrum bytes."
  [spectrum]
  (let [ls (pure/bar-levels spectrum)]
    (if (seq ls) (min 1 (* 1.6 (/ (reduce + ls) (count ls)))) 0)))
