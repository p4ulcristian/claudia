(ns claudia.web.world.labels
  "Names over the world as plain HTML (crisp at any size, no textures): one element per label in a
  layer over the canvas, moved every frame to where its point lands on the screen.")

(defn make-layer
  "The labels' layer inside host."
  [^js host]
  (let [el (js/document.createElement "div")]
    (set! (.-className el) "world-labels")
    (set! (.-els el) (js/Map.))
    (.appendChild host el)
    el))

(defn- label-el [{:keys [kind]}]
  (let [el (js/document.createElement "div")]
    (set! (.-className el) (str "world-label " (name kind)))
    el))

(defn sync!
  "The labels shown: items [{:id :text :kind (:island | :robot) :color (CSS colour) :note}], made,
  changed and removed by id."
  [^js layer items]
  (let [^js els (.-els layer)
        ids (set (map :id items))]
    (doseq [id (js/Array.from (.keys els)) :when (not (ids id))]
      (.remove ^js (.get els id)) (.delete els id))
    (doseq [{:keys [id text color note] :as item} items]
      (let [^js el (or (.get els id) (let [e (label-el item)] (.appendChild layer e) (.set els id e) e))]
        (set! (.-textContent el) text)
        (when note (.setAttribute el "data-note" note))
        (.setProperty (.-style el) "--label" (or color ""))))))

(defn place!
  "Every label moved to where its point is on screen (point-of: id -> [x y z] or nil); hidden behind
  the camera or without a point."
  [^js layer ^js camera w h point-of ^js v]
  (.forEach ^js (.-els layer)
            (fn [^js el id]
              (if-let [[x y z] (point-of id)]
                (let [_ (.project (.set v x y z) camera)
                      shown? (< -1 (.-z v) 1)]
                  (set! (.. el -style -transform)
                        (str "translate(" (* w (/ (inc (.-x v)) 2)) "px," (* h (/ (- 1 (.-y v)) 2)) "px)"))
                  (set! (.. el -style -visibility) (if shown? "visible" "hidden")))
                (set! (.. el -style -visibility) "hidden")))))
