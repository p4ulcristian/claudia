(ns claudia.web.views.claudia-rain
  "Behind Claudia's own chat (README decision 50): a few columns of green code falling, very faint and
  sparse, over her chat's dark green gradient. A canvas fixed behind the page's content; the falling
  is pure (columns in, columns out), the drawing a small edge. None when the reader asks for less
  motion."
  (:require [reagent.hooks :as h]
            [claudia.web.hooks :as hooks]))

(def ^:private glyphs (vec "01アイウエオカキクケコサシスセソタチツテト{}<>=/;:+*#"))
(def ^:private step-px 22)

(defn columns
  "n columns over a width w and height h, from random numbers rs (3 each, 0 to 1): x, where its head
  is, how fast it falls (px a second)."
  [n w h rs]
  (vec (for [[i [a b c]] (map-indexed vector (partition 3 rs)) :when (< i n)]
         {:x (* w a) :y (* h b) :speed (+ 18 (* 30 c)) :seed (int (* 1000 c))})))

(defn fall
  "Columns dt ms later; one past the bottom h starts again above the top."
  [cols dt h]
  (mapv (fn [{:keys [y speed] :as c}]
          (let [y2 (+ y (/ (* speed dt) 1000))]
            (assoc c :y (if (> y2 (+ h (* 6 step-px))) (- (* 2 step-px)) y2))))
        cols))

(defn trail
  "A column's glyphs at time t (ms): [x y glyph alpha], the head brightest, five fading above it."
  [{:keys [x y seed]} t]
  (for [k (range 6)
        :let [g (glyphs (mod (+ seed k (quot t 600)) (count glyphs)))]]
    [x (- y (* k step-px)) g (* (- 1 (/ k 6)) (if (zero? k) 1 0.6))]))

(defn- draw! [^js ctx cols t w h]
  (.clearRect ctx 0 0 w h)
  (set! (.-font ctx) "16px ui-monospace, monospace")
  (doseq [c cols [x y g a] (trail c t)]
    (set! (.-fillStyle ctx) (str "rgba(60,255,106," (* 0.09 a) ")"))
    (.fillText ctx g x y)))

(defn- fit!
  "The canvas sized to the window; returns [w h]."
  [^js canvas]
  (let [w js/innerWidth h js/innerHeight]
    (set! (.-width canvas) w)
    (set! (.-height canvas) h)
    [w h]))

(defn- start!
  "Keeps the code falling in the canvas, about eight frames a second; returns what stops it."
  [^js canvas calm?]
  (let [ctx (.getContext canvas "2d")
        [w0 h0] (fit! canvas)
        cols (atom (columns 9 w0 h0 (repeatedly 27 js/Math.random)))
        prev (atom (js/performance.now))
        stopped (atom false)
        on-resize #(fit! canvas)]
    (.addEventListener js/window "resize" on-resize)
    (letfn [(step [now]
              (when-not @stopped
                (let [w (.-width canvas) h (.-height canvas)]
                  (swap! cols fall (if calm? 0 (- now @prev)) h)
                  (reset! prev now)
                  (draw! ctx @cols now w h)
                  (js/setTimeout #(js/requestAnimationFrame step) 120))))]
      (js/requestAnimationFrame step))
    #(do (reset! stopped true) (.removeEventListener js/window "resize" on-resize))))

(defn rain
  "The faint code behind her chat (use as [:f> rain])."
  []
  (let [ref (h/use-ref nil)
        calm? (hooks/use-media "(prefers-reduced-motion: reduce)")]
    (h/use-effect (fn [] (start! (.-current ref) calm?)) [calm?])
    [:canvas.claudia-rain {:ref ref :aria-hidden true}]))
