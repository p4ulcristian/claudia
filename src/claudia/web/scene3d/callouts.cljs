(ns claudia.web.scene3d.callouts
  "The callout layer over a 3D scene: the label elements (in .s3-callouts) and a canvas for the
  leader lines and their dots (.s3-leaders). Each frame the engine hands over the labels to show,
  each with its anchor's screen point; this places them in their columns (claudia.web.scene3d.labels),
  eases them there and draws the lines. Neither layer takes the pointer: the scene under them does."
  (:require [claudia.web.scene3d.labels :as labels]))

(defn make!
  "The two layers, added to the stage. -> the callouts {:layer :canvas :at (atom {id y})}."
  [^js stage]
  (let [canvas (js/document.createElement "canvas")
        layer (js/document.createElement "div")]
    (set! (.-className canvas) "s3-leaders")
    (set! (.-className layer) "s3-callouts")
    (.append stage canvas layer)
    {:layer layer :canvas canvas :at (atom {})}))

(defn add!
  "Puts a label element in a callout layer (an element), hidden until a frame shows it."
  [^js layer ^js el]
  (set! (.. el -style -display) "none")
  (.append layer el))

(defn clear!
  "Every label goes (the scene is built again)."
  [{:keys [^js layer at]}]
  (set! (.-textContent layer) "")
  (reset! at {}))

(defn size!
  "The leader canvas at the stage's size and the screen's pixel ratio."
  [{:keys [^js canvas]} w h]
  (let [r (min js/devicePixelRatio 2)]
    (set! (.-width canvas) (js/Math.round (* w r)))
    (set! (.-height canvas) (js/Math.round (* h r)))))

(defn- measured [{:keys [^js el] :as it}]
  (set! (.. el -style -display) "")
  (assoc it :w (.-offsetWidth el) :h (.-offsetHeight el)))

(defn- line! [^js ctx [[x y] [bx by] [ex ey]] color]
  (set! (.-strokeStyle ctx) color)
  (set! (.-fillStyle ctx) color)
  (.beginPath ctx) (.moveTo ctx x y) (.lineTo ctx bx by) (.lineTo ctx ex ey) (.stroke ctx)
  (.beginPath ctx) (.arc ctx x y 3.2 0 (* 2 js/Math.PI)) (.fill ctx))

(defn- place!
  "Moves a label towards its place (eased) and draws its leader. -> whether it is still moving."
  [{:keys [at]} ^js ctx {:keys [id ^js el w color opacity] :as it} spot]
  (let [y0 (get @at id)
        y (labels/ease y0 (:top spot))
        p (assoc spot :top y :cy (+ y (- (:cy spot) (:top spot))))]
    (swap! at assoc id y)
    (set! (.. el -style -transform) (str "translate(" (.toFixed (:left p) 1) "px," (.toFixed y 1) "px)"))
    (set! (.. el -style -opacity) (str opacity))
    (set! (.-globalAlpha ctx) (* 0.85 opacity))
    (line! ctx (labels/leader it p w) color)
    (not= y (:top spot))))

(defn frame!
  "Shows these labels (items {:id :el :x :y :color :opacity}) and hides the rest of hide (elements),
  in columns beside the model's screen rect, on a stage w by h, clear of the obstacles (screen
  rects). -> whether any still eases."
  [{:keys [^js canvas at] :as c} items hide rect w h obstacles]
  (doseq [^js el hide] (set! (.. el -style -display) "none"))
  (swap! at select-keys (map :id items))
  (let [^js ctx (.getContext canvas "2d")
        r (/ (.-width canvas) (max w 1))
        shown (mapv measured items)
        spots (labels/layout shown rect w h obstacles)]
    (.setTransform ctx r 0 0 r 0 0)
    (.clearRect ctx 0 0 w h)
    (set! (.-lineWidth ctx) 1.2)
    (doseq [{:keys [id ^js el]} shown :when (not (spots id))] (set! (.. el -style -display) "none"))
    (boolean (some identity (doall (for [it shown :let [spot (spots (:id it))] :when spot] (place! c ctx it spot)))))))
