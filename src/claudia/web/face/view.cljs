(ns claudia.web.face.view
  "Claudia's face, alive in the menu button (2026-11-05; it replaced the three strokes). She watches
  the page (claudia.web.face.mind): chats working, waiting on you, done, failed, new or gone, the
  list opening, you typing, the mouse coming near, the page coming back after a while; and lives on
  her own when nothing happens (claudia.web.face.clips). Drawn once as SVG; a frame loop (at most
  30 a second, none while the page is hidden) writes only the attributes that changed."
  (:require [re-frame.db :as rdb]
            [reagent.hooks :as h]
            [claudia.web.face.art :as art]
            [claudia.web.face.mind :as mind]
            [claudia.web.face.paint :as paint]
            [claudia.web.hooks :as hooks]))

(defonce ^:private brain (atom nil))

;; ---- the drawing ----

(defn- eye-svg [side]
  (let [[x y] (art/eye-at side) s (name side)]
    [:<>
     [:g {:data-part (str "eye-" s "-dot")}
      [:ellipse {:cx x :cy y :rx 17 :ry 22 :fill art/ink}]
      [:circle {:cx (- x 7) :cy (- y 8) :r 5 :fill "#fff"}]]
     [:path {:data-part (str "eye-" s "-line") :fill "none" :stroke art/ink :stroke-width 9
             :stroke-linecap "round" :stroke-linejoin "round"}]
     [:path {:data-part (str "eye-" s "-fill") :fill art/ink :stroke art/ink :stroke-width 4}]]))

(defn- cheek-svg [side]
  (let [[x y rx ry] (art/cheeks side)]
    [:ellipse {:data-part (str "blush-" (name side)) :cx x :cy y :rx rx :ry ry :fill art/cheek
               :filter "url(#face-soft)"}]))

(defn- face-svg []
  [:g {:data-part "face"}
   [:path {:d art/face-shape :fill art/skin :stroke art/ink :stroke-width 7 :stroke-linejoin "round"}]
   [:g {:data-part "feat"}
    [cheek-svg :far] [cheek-svg :near] [eye-svg :far] [eye-svg :near]
    [:path {:data-part "tongue" :d art/tongue :fill art/cheek :stroke art/ink :stroke-width 5}]
    [:path {:data-part "mouth" :stroke art/ink :stroke-width 9 :stroke-linecap "round" :stroke-linejoin "round"}]]])

(defn- hair-svg []
  [:<>
   [:g {:data-part "lock"}
    [:path {:d art/lock-fill :fill art/hair}]
    [:path {:d art/lock-edge :fill "none" :stroke art/ink :stroke-width 7 :stroke-linecap "round"}]]
   [:g {:data-part "clip"}
    [:g {:data-part "spin"}
     [:path {:transform art/spark-transform :d art/spark :fill art/gold :stroke art/ink :stroke-width 16
             :paint-order "stroke"}]]]])

(defn- head-svg [ref]
  [:svg.face {:ref ref :viewBox art/view-box :aria-hidden true}
   [:defs [:filter#face-soft {:x "-50%" :y "-50%" :width "200%" :height "200%"} [:feGaussianBlur {:stdDeviation 7}]]]
   [:g {:data-part "body"}
    [:g {:data-part "back"}
     [:path {:d art/back-hair :fill art/hair :stroke art/ink :stroke-width 7 :stroke-linejoin "round"}]]
    [face-svg]
    [hair-svg]]
   [:g {:data-part "fx"}]])

;; ---- the edges: writing the drawing, listening to the page ----

(defn- parts-of
  "The drawing's parts by name (keyword), from its data-part attributes."
  [^js svg]
  (into {} (for [^js el (array-seq (.querySelectorAll svg "[data-part]"))]
             [(keyword (.getAttribute el "data-part")) el])))

(defn- write!
  "Sets the attributes that differ from what was last written (written: a JS Map of
  \"part attr\" -> value)."
  [parts ^js written attrs]
  (doseq [[part kv] attrs
          :let [^js el (parts part)]
          :when el
          [a v] kv
          :let [k (str (name part) " " (name a))]
          :when (not= v (.get written k))]
    (.set written k v)
    (if (= a :html) (set! (.-innerHTML el) v) (.setAttribute el (name a) v))))

(defn- frame!
  "One frame at now: her next step from the last frame's time, drawn."
  [parts written last-at calm?]
  (let [now (js/performance.now)
        dt (min 100 (- now @last-at))]
    (when (>= dt 33)
      (reset! last-at now)
      (let [rs (repeatedly 4 #(js/Math.random))
            state (swap! brain mind/tick now dt rs (.getHours (js/Date.)))]
        (write! parts written (paint/attrs state now @calm?))))))

(defn- watched-changed?
  "Whether app-db changed in a part she watches."
  [before after]
  (some #(not (identical? (% before) (% after))) [:servers :chats :sidebar? :open :outbox :drafts :error]))

(defn- on-db!
  "app-db changed: the new world, and her reactions to what happened."
  [_ _ before after]
  (when (watched-changed? before after)
    (let [now (js/performance.now)
          evs (mind/db-events before after)]
      (swap! brain (fn [s] (reduce #(mind/react %1 %2 now) (assoc s :world (mind/world-of after)) evs))))))

(defn- pointer-at
  "The mouse relative to her (her own sizes: 1 = 250 px), nil when it is far."
  [^js svg ^js e]
  (let [r (.getBoundingClientRect svg)
        dx (/ (- (.-clientX e) (+ (.-left r) (/ (.-width r) 2))) 250)
        dy (/ (- (.-clientY e) (+ (.-top r) (/ (.-height r) 2))) 250)]
    (when (< (js/Math.hypot dx dy) 3)
      [(max -1 (min 1 dx)) (max -1 (min 1 dy))])))

(defn- react! [ev] (swap! brain mind/react ev (js/performance.now)))

(defn- page-listeners
  "Window and document events she reacts to, as [target event handler]."
  [^js svg]
  [[js/window "pointermove" #(swap! brain mind/point (pointer-at svg %) (js/performance.now))]
   [js/document "visibilitychange" #(react! (if js/document.hidden :hidden :visible))]
   [js/window "dragenter" #(when (some #{"Files"} (array-seq (.. % -dataTransfer -types))) (react! :drag))]
   [(.-parentElement svg) "pointerenter" #(react! :hover-in)]
   [(.-parentElement svg) "pointerleave" #(react! :hover-out)]
   [(.-parentElement svg) "click" #(react! :click)]])

(defn- listen!
  "Adds the listeners; returns what removes them."
  [listeners]
  (doseq [[^js target ev f] listeners] (.addEventListener target ev f))
  #(doseq [[^js target ev f] listeners] (.removeEventListener target ev f)))

(defn- start!
  "Brings her to life in the drawn svg: watches app-db and the page, runs the frame loop. Returns
  what stops it all."
  [^js svg calm?]
  (when (nil? @brain) (reset! brain (mind/initial (js/performance.now))))
  (swap! brain assoc :world (mind/world-of @rdb/app-db))
  (add-watch rdb/app-db ::face on-db!)
  (let [parts (parts-of svg)
        written (js/Map.)
        last-at (atom 0)
        stopped (atom false)
        unlisten (listen! (page-listeners svg))
        step (fn step []
               (when-not @stopped
                 (frame! parts written last-at calm?)
                 (js/requestAnimationFrame step)))]
    (js/requestAnimationFrame step)
    #(do (reset! stopped true) (unlisten) (remove-watch rdb/app-db ::face))))

(defn face
  "Claudia's face, alive (use as [:f> face] inside the button it belongs to)."
  []
  (let [ref (h/use-ref nil)
        calm (hooks/use-media "(prefers-reduced-motion: reduce)")
        calm? (h/use-memo #(atom calm) [])]
    (h/use-effect (fn [] (reset! calm? calm) nil) [calm])
    (h/use-effect (fn [] (start! (.-current ref) calm?)) [])
    [head-svg ref]))
