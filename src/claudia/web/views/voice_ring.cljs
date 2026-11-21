(ns claudia.web.views.voice-ring
  "Bars round the circle of the chat whose reply is read aloud now (README decision 21), on the rail
  and in the list: the same bars as round the held mic (`pure/bar-levels`), following the voice, in
  the chat's colour."
  (:require [claudia.web.speech :as speech]
            [claudia.web.views.mic-flower :as flower]
            [claudia.web.views.pure.mic :as pure]
            [reagent.hooks :as h]))

(def size "The canvas's side in CSS pixels, centred on the circle." 64)
(def ^:private bar-r "Where the bars start, from the circle's centre (its edge is 17)." 19)
(def ^:private bar-len "A full bar's length." 10)

(defn- draw!
  "One frame: a bar per level (0-1), the first pointing right, in the chat's colour."
  [^js canvas levels]
  (let [g (.getContext canvas "2d") dpr (or js/devicePixelRatio 1)]
    (.setTransform g 1 0 0 1 0 0)
    (.clearRect g 0 0 (.-width canvas) (.-height canvas))
    (.setTransform g dpr 0 0 dpr 0 0)
    (.translate g (/ size 2) (/ size 2))
    (set! (.-fillStyle g) (flower/css canvas "--chat"))
    (doseq [[i level] (map-indexed vector levels)]
      (.save g)
      (.rotate g (* 2 js/Math.PI (/ i pure/bar-count)))
      (.fillRect g bar-r -0.75 (+ 1.5 (* level bar-len)) 1.5)
      (.restore g))))

(defn- use-bars!
  "Draws the bars every frame while mounted."
  [canvas]
  (h/use-effect
   (fn []
     (let [raf (atom nil) eased (atom [])
           step (fn step []
                  (when-let [c (.-current canvas)]
                    (let [{:keys [spectrum rate]} (speech/spectrum)]
                      (swap! eased pure/eased (if spectrum (pure/bar-levels spectrum rate) (repeat pure/bar-count 0)))
                      (draw! c @eased)))
                  (reset! raf (js/requestAnimationFrame step)))]
       (step)
       #(some-> @raf js/cancelAnimationFrame)))
   []))

(defn ring
  "The bars' canvas (a function component: [:f> ring])."
  []
  (let [canvas (h/use-ref nil)
        px (* size (or js/devicePixelRatio 1))]
    (use-bars! canvas)
    [:canvas.voice-ring {:ref #(set! (.-current canvas) %) :aria-hidden true
                         :width px :height px :style {:width size :height size}}]))
