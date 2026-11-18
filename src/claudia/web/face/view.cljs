(ns claudia.web.face.view
  "Claudia's face, alive at the rail's top (2026-11-05; the noir look 2026-11-18). She watches
  the page (claudia.web.face.mind): chats working, waiting on you, done, failed, new or gone, the
  list opening, you typing, the mouse coming near, the page coming back after a while; and lives on
  her own when nothing happens (claudia.web.face.clips). A chat's news types out on her lenses while
  she looks at its circle in the rail; a click then opens that chat. Drawn once as SVG; a frame loop
  (at most 30 a second, none while the page is hidden) writes only the attributes that changed."
  (:require [re-frame.db :as rdb]
            [reagent.hooks :as h]
            [re-frame.core :as rf]
            [claudia.web.face.art :as art]
            [claudia.web.logo :as logo]
            [claudia.web.face.mind :as mind]
            [claudia.web.face.paint :as paint]
            [claudia.web.hooks :as hooks]))

(defonce ^:private brain (atom nil))

;; ---- the drawing ----

(defn- eye-svg
  "One drawn eye (side :far or :near): white and iris inside the almond, a skin lid that comes down
  over it, the lid lines on top."
  [side]
  (let [{:keys [almond flick] [bx _ bw] :box} (art/eyes side) p (name side)]
    [:<>
     [:clipPath {:id (str "face-eye-" p)} [:path {:d almond}]]
     [:g {:data-part (str "open-" p) :clip-path (str "url(#face-eye-" p ")")}
      [:rect {:x bx :y 240 :width bw :height 70 :fill art/sclera}]
      [:use {:data-part (str "iris-" p) :href "#face-iris"}]
      [:rect {:data-part (str "cover-" p) :x bx :width bw :fill art/skin}]]
     [:g {:fill "none" :stroke art/ink :stroke-linecap "round" :stroke-linejoin "round"}
      [:path {:data-part (str "lid-" p) :stroke-width 4.5}]
      [:path {:d flick :stroke-width 3.2}]
      [:path {:data-part (str "low-" p) :d (:low (art/eyes side)) :stroke-width 1.6}]]]))

(defn- defs-svg []
  [:defs
   [:radialGradient#face-iris-fill {:cx 0.5 :cy 0.35 :r 0.7}
    [:stop {:offset 0 :stop-color "#8dffbd"}] [:stop {:offset 0.4 :stop-color "#22c866"}]
    [:stop {:offset 1 :stop-color "#08502a"}]]
   [:filter#face-soft [:feGaussianBlur {:stdDeviation 1.4}]]
   [:g#face-iris [:ellipse {:rx 12 :ry 14 :fill "url(#face-iris-fill)" :stroke "#06301a" :stroke-width 1.8}]
    [:ellipse {:rx 4.5 :ry 5.5 :fill "#04160c"}] [:circle {:cx -4 :cy -6 :r 2.4 :fill "#fff"}]]
   (for [[k ls] art/lenses]
     ^{:key k} [:clipPath {:id (str "face-lens-" (name k))}
                (for [[i [cx cy rx ry]] (map-indexed vector ls)] ^{:key i} [:ellipse {:cx cx :cy cy :rx rx :ry ry}])])
   [:linearGradient#face-gold {:x1 0 :y1 0 :x2 1 :y2 1}
    [:stop {:offset 0 :stop-color "#fff3b0"}] [:stop {:offset 0.45 :stop-color "#f2c94c"}]
    [:stop {:offset 1 :stop-color "#b8862b"}]]])

(defn- eyes-svg []
  [:g {:data-part "eyes"}
   [:g {:fill art/skin :filter "url(#face-soft)"} (for [d art/eye-cover] ^{:key d} [:path {:d d}])]
   [eye-svg :far] [eye-svg :near]])

(defn- head-svg [ref]
  [:svg.face {:ref ref :data-part "root" :viewBox art/view-box :aria-hidden true}
   [defs-svg]
   [:g {:data-part "body"}
    [:image {:data-part "up" :href (:up art/pictures) :width 512 :height 512}]
    [:image {:data-part "down" :href (:down art/pictures) :width 512 :height 512 :opacity 0}]
    [eyes-svg]
    [:g {:data-part "lens-box"} [:g {:data-part "lens"}]]
    [:g {:data-part "spark"}
     [:path {:d logo/spark :fill "url(#face-gold)" :stroke "#2a1f12" :stroke-width 12 :stroke-linejoin "round"
             :paint-order "stroke"}]]]])

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

(defn- chat-aim
  "Where the chat k's circle is seen from her face, as a look [x y] (-1 to 1), nil when not shown."
  [^js svg [s id]]
  (when-let [^js el (.querySelector js/document (str ".rail-chat[data-slide=\"" s "/" id "\"]"))]
    (let [a (.getBoundingClientRect svg) b (.getBoundingClientRect el)
          dx (- (+ (.-left b) (/ (.-width b) 2)) (+ (.-left a) (/ (.-width a) 2)))
          dy (- (+ (.-top b) (/ (.-height b) 2)) (+ (.-top a) (/ (.-height a) 2)))
          n (max 1 (js/Math.hypot dx dy))]
      [(/ dx n) (/ dy n)])))

(defn- aim-ticker
  "Her ticker's chat, looked at (its circle may have moved since the last frame)."
  [state svg]
  (if-let [k (get-in state [:ticker :k])]
    (assoc-in state [:ticker :aim] (chat-aim svg k))
    state))

(defn- frame!
  "One frame at now: her next step from the last frame's time, drawn."
  [parts written last-at calm?]
  (let [now (js/performance.now)
        dt (min 100 (- now @last-at))]
    (when (>= dt 33)
      (reset! last-at now)
      (let [rs (repeatedly 4 #(js/Math.random))
            state (swap! brain #(-> (aim-ticker % (:root parts))
                                    (mind/tick now dt rs (.getHours (js/Date.)))))]
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

(defn click!
  "A click on her: the chat her lenses talk about opens; else her own chat (:claudia/open)."
  []
  (if-let [k (get-in @brain [:ticker :k])]
    (do (swap! brain mind/clear-ticker) (rf/dispatch [:nav/open k]))
    (rf/dispatch [:claudia/open])))

(defn face
  "Claudia's face, alive (use as [:f> face] inside the button it belongs to)."
  []
  (let [ref (h/use-ref nil)
        calm (hooks/use-media "(prefers-reduced-motion: reduce)")
        calm? (h/use-memo #(atom calm) [])]
    (h/use-effect (fn [] (reset! calm? calm) nil) [calm])
    (h/use-effect (fn [] (start! (.-current ref) calm?)) [])
    [head-svg ref]))
