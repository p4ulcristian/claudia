(ns claudia.web.world.boards
  "Every island's board, hung on its cliff facing the camera: the server's name, one word for how it
  is doing (claudia.web.world.health) and its CPU, memory and GPU as bars (claudia.web.world.atlas)."
  (:require [claudia.web.world.atlas :as atlas]
            [claudia.web.world.health :as health]))

(def ^:private spec {:px-w 512 :px-h 256 :cols 2 :rows 4 :width 6.4})

(defn make [scene] (atlas/make scene spec))

(def ^:private font "'Atkinson Hyperlegible', system-ui, sans-serif")

(defn- text! [^js ctx s x y size weight colour]
  (set! (.-font ctx) (str weight " " size "px " font))
  (set! (.-fillStyle ctx) colour)
  (.fillText ctx s x y))

(defn- bar! [^js ctx x y label value]
  (text! ctx label x y 24 700 "#c9ced8")
  (set! (.-fillStyle ctx) "rgba(255,255,255,0.12)")
  (.fillRect ctx (+ x 70) (- y 11) 300 22)
  (when value
    (set! (.-fillStyle ctx) (cond (>= value 85) "#ff7a59" (>= value 50) "#ffd36b" :else "#8dffb0"))
    (.fillRect ctx (+ x 70) (- y 11) (* 3 (min 100 value)) 22))
  (text! ctx (if value (str value "%") "–") (+ x 385) y 24 700 "#eef1f6"))

(defn- card! [^js ctx x y w h]
  (.beginPath ctx)
  (.roundRect ctx (+ x 6) (+ y 6) (- w 12) (- h 12) 24)
  (set! (.-fillStyle ctx) "rgb(24,28,40)")
  (.fill ctx)
  (set! (.-lineWidth ctx) 5)
  (set! (.-strokeStyle ctx) "#f3efe6")
  (.stroke ctx))

(defn- draw! [^js ctx x y w h {:keys [label status load]}]
  (card! ctx x y w h)
  (set! (.-textBaseline ctx) "middle")
  (text! ctx label (+ x 28) (+ y 46) 38 700 "#fffdf6")
  (text! ctx (health/words status) (+ x 300) (+ y 46) 28 700 (health/colours status))
  (bar! ctx (+ x 28) (+ y 108) "CPU" (:cpu load))
  (bar! ctx (+ x 28) (+ y 154) "RAM" (:mem load))
  (bar! ctx (+ x 28) (+ y 200) "GPU" (:gpu load)))

(defn sync!
  "The boards for islands (the plan's, in order); info-of: island id -> {:status :load}."
  [rt islands info-of]
  (atlas/sync! rt (map #(merge % (info-of (:id %))) islands)
               (juxt :label :status :load) draw!))

(defn- spot
  "An island's board: on its cliff on the side facing the camera."
  [{:keys [pos radius]} ^js cam]
  (let [[x y z] pos
        dx (- (.-x cam) x) dz (- (.-z cam) z) d (max 0.001 (js/Math.hypot dx dz))]
    [(+ x (* (+ radius 0.6) (/ dx d))) (- y 1.6) (+ z (* (+ radius 0.6) (/ dz d)))]))

(defn frame! [rt ^js camera]
  (atlas/frame! rt #(atlas/facing (spot % (.-position camera)) (.-position camera) -0.15)))

(defn pick
  "The island whose board is under the raycaster, or nil."
  [rt raycaster]
  (atlas/pick rt raycaster))

(defn rect
  "Where island id's board is on the page (px), or nil."
  [rt id camera canvas-rect]
  (atlas/rect rt id camera canvas-rect))

(defn snapshot
  "A copy of island id's board as a canvas, or nil."
  [rt id]
  (atlas/snapshot rt id))
