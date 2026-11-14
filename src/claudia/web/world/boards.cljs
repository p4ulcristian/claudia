(ns claudia.web.world.boards
  "Every island's board, hung on its cliff facing the camera: the server's name, one word for how it
  is doing (claudia.web.world.health) and as bars its CPU (with its cores), memory and the graphics
  card's use and memory (with the GB behind them; claudia.web.world.atlas)."
  (:require [claudia.web.world.atlas :as atlas]
            [claudia.web.world.health :as health]))

(def ^:private spec {:px-w 512 :px-h 320 :cols 2 :rows 4 :width 6.4})

(defn make
  "The boards, and the halo that frames the selected one (its Systems card is open)."
  [^js scene]
  (let [rt (atlas/make scene spec)
        halo (atlas/make-halo (:width spec) (* (:width spec) (/ (:px-h spec) (:px-w spec))))]
    (.add scene halo)
    (set! (.-halo rt) halo)
    rt))

(def ^:private font "'Atkinson Hyperlegible Next', system-ui, sans-serif")

(defn- text! [^js ctx s x y size weight colour]
  (set! (.-font ctx) (str weight " " size "px " font))
  (set! (.-fillStyle ctx) colour)
  (.fillText ctx s x y))

(defn- bar!
  "A labelled bar at value percent, the percent and detail (cores, GB) beside it."
  [^js ctx x y label value detail]
  (text! ctx label x y 22 700 "#c9ced8")
  (set! (.-fillStyle ctx) "rgba(255,255,255,0.12)")
  (.fillRect ctx (+ x 74) (- y 10) 190 20)
  (when value
    (set! (.-fillStyle ctx) (cond (>= value 85) "#ff7a59" (>= value 50) "#ffd36b" :else "#8dffb0"))
    (.fillRect ctx (+ x 74) (- y 10) (* 1.9 (min 100 value)) 20))
  (text! ctx (if value (str value "%") "–") (+ x 276) y 22 700 "#eef1f6")
  (when detail (text! ctx detail (+ x 342) y 19 400 "#aeb6c4")))

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
  (bar! ctx (+ x 28) (+ y 110) "CPU" (:cpu load) (some-> (:cores load) (str " cores")))
  (bar! ctx (+ x 28) (+ y 160) "RAM" (:mem load) (health/gb-text (:mem-used load) (:mem-total load)))
  (bar! ctx (+ x 28) (+ y 210) "GPU" (:gpu load) nil)
  (bar! ctx (+ x 28) (+ y 260) "VRAM" (:vram load) (health/gb-text (:vram-used load) (:vram-total load))))

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

(defn frame! [^js rt ^js camera]
  (atlas/frame! rt #(atlas/facing (spot % (.-position camera)) (.-position camera) -0.15) (.-selected rt) (.-halo rt)))

(defn pick
  "The island whose board is under the raycaster, or nil."
  [rt raycaster]
  (atlas/pick rt raycaster))
