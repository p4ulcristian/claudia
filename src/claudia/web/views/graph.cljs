(ns claudia.web.views.graph
  "A graph block: laid out by ELK (layered), drawn as SVG in chalk. Three layouts are tried (left to
  right, top to bottom, top to bottom with groups stacked) and the one that fits the pane best is
  kept; it changes only when another fits clearly better, so the picture doesn't jump. Nodes and
  arrows move to their new places with CSS transitions; new ones pop in."
  (:require ["elkjs/lib/elk.bundled.js" :as ELK]
            [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.web.chalk :as chalk]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.graph :as pure]))

(defonce ^:private elk (ELK.))

(def ^:private colors ["green" "red" "amber" "blue" "grey" "violet"])
(def ^:private font "700 15px Kalam, cursive")
(def ^:private group-font "700 14px Kalam, cursive")
(def ^:private note-font "400 12px \"Atkinson Hyperlegible Next\", system-ui, sans-serif")
(def ^:private note-line pure/note-line)
(def ^:private pad pure/pad)

(defonce ^:private ctx (.getContext (js/document.createElement "canvas") "2d"))

(defn- text-w
  ([s] (text-w s font))
  ([s f] (set! (.-font ctx) f) (.-width (.measureText ctx s))))

(defn- note-w [s] (text-w s note-font))
(defn- group-w [s] (text-w s group-font))

(defn- layout
  "A promise of the laid-out graph for one variant (:right :down :down-stacked)."
  [{:keys [nodes] :as g} variant]
  (let [sizes (into {} (map (juxt :id #(pure/node-size % text-w note-w))) nodes)]
    (-> (.layout elk (clj->js (pure/elk-graph g variant sizes group-w note-w)))
        (.then #(pure/laid-out (js->clj % :keywordize-keys true) variant sizes g)))))

(def ^:private variants [:right :down :down-stacked])

(defn- node-shape [shape cls {:keys [w h d]} cx cy]
  (case shape
    "circle" [:circle {:class cls :cx cx :cy cy :r (/ d 2)}]
    "diamond" [:polygon {:class cls :points (str cx ",1 " (dec w) "," cy " " cx "," (dec h) " 1," cy)}]
    [:rect {:class cls :x 0.75 :y 0.75 :width (- w 1.5) :height (- h 1.5)
            :rx (if (= shape "pill") (/ h 2) 9)}]))

(defn- node-ring
  "A changed node rings three times; a new key per draw starts it again."
  [shape {:keys [w h d]} cx cy pulse]
  (if (= shape "circle")
    ^{:key pulse} [:circle.ring {:cx cx :cy cy :r (+ 5 (/ d 2))}]
    ^{:key pulse} [:rect.ring {:x -5 :y -5 :width (+ w 10) :height (+ h 10) :rx (if (= shape "pill") (+ 5 (/ h 2)) 13)}]))

(defn- node-badge [state bx by]
  (case state
    "done" [:g.badge.done {:transform (str "translate(" bx "," by ")")}
            [:circle {:r 8}] [:path {:d "M-3.5,0 L-1,2.6 L3.8,-2.6"}]]
    "blocked" [:g.badge.blocked {:transform (str "translate(" bx "," by ")")}
               [:circle {:r 8}] [:path {:d "M-3,-3 L3,3 M3,-3 L-3,3"}]]
    nil))

(defn- node-texts [{:keys [d lines notes]} circle? cx text-top]
  [:<>
   (into [:text.label {:x cx :y text-top :text-anchor "middle" :dominant-baseline "middle"}]
         (map-indexed (fn [i l] [:tspan {:key i :x cx :dy (if (pos? i) 16 0)} l]) lines))
   (when (seq notes)
     (into [:text.note {:x cx :y (if circle? (+ d 15) (+ text-top (* 16 (dec (count lines))) 17))
                        :text-anchor "middle" :dominant-baseline "middle"}]
           (map-indexed (fn [i l] [:tspan {:key i :x cx :dy (if (pos? i) note-line 0)} l]) notes)))])

(defn- node-view [{:keys [x y w h d lines notes node] :as n} fresh? pulse]
  (let [shape (or (:shape node) "circle")
        circle? (= shape "circle")
        cx (/ w 2)
        cy (if circle? (/ d 2) (/ h 2))
        text-top (- cy (/ (* 16 (dec (count lines))) 2) (if circle? 0 (/ (* note-line (count notes)) 2)))
        color (pure/known-color colors (:color node) "blue")]
    [:g {:class (str "node c-" color " s-" (or (:state node) "none") (when fresh? " fresh"))
         :style {:transform (str "translate(" x "px, " y "px)")}}
     (when (pos? pulse) (node-ring shape n cx cy pulse))
     (node-shape shape "fray" n cx cy)
     (node-shape shape "body" n cx cy)
     (node-texts n circle? cx text-top)
     (node-badge (:state node)
                 (if circle? (+ cx (* d 0.36)) (- w 4))
                 (if circle? (- cy (* d 0.36)) 4))]))

(defn- avail-size
  "The room for a graph: its block's width (or the scroller's, less its padding) and 80% of the
  scroller's height."
  [^js el]
  (let [^js stage (some-> el (.closest ".right-scroll"))
        w (let [cw (some-> el .-clientWidth)] (if (pos? cw) cw (- (or (some-> stage .-clientWidth) 800) 36)))]
    [w (max 320 (* 0.8 (or (some-> stage .-clientHeight) 700)))]))

;; ---- the hooks ----

(defn- use-wheel-zoom!
  "Ctrl+wheel and trackpad pinch zoom the picture, not the page: a native listener, as React's
  wheel listener is passive and can't stop the browser's zoom."
  [box zoom-at laid]
  (h/use-effect
   (fn []
     (when-let [^js el (.-current box)]
       (let [wheel (fn [^js e]
                     (when (or (.-ctrlKey e) (.-metaKey e))
                       (.preventDefault e)
                       (let [r (.getBoundingClientRect el)]
                         (zoom-at (- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r)) (js/Math.exp (* -0.0025 (.-deltaY e)))))))]
         (.addEventListener el "wheel" wheel #js {:passive false})
         #(.removeEventListener el "wheel" wheel))))
   [laid]))

(defn- use-width-steps!
  "Lay out again when the block's width changes noticeably (window resize, divider drag): set-width
  gets the new width 150 ms after it settles, when it moved more than 40 px."
  [box set-width]
  (let [t (h/use-ref nil)]
    (hooks/use-resize box
                      (fn [^js el]
                        (let [w (.-clientWidth el)]
                          (js/clearTimeout (.-current t))
                          (set! (.-current t) (js/setTimeout #(set-width (fn [old] (if (> (js/Math.abs (- w old)) 40) w old))) 150))))
                      [])
    (h/use-effect (fn [] #(js/clearTimeout (.-current t))) [])))

(defn- shown!
  "A new layout to show: remember its variant and which ids are new since the last one."
  [{:keys [kept fresh known]} l]
  (let [ids (pure/ids-of l)]
    (set! (.-current kept) (:variant l))
    (set! (.-current fresh) (into #{} (remove (.-current known)) ids))
    (set! (.-current known) ids)))

(defn- lay-out!
  "Every variant laid out once the fonts are there; the one chosen goes to set-laid (unless gone)."
  [g {:keys [box kept] :as memory} set-laid gone]
  (let [[aw ah] (avail-size (.-current box))]
    (-> (.-ready js/document.fonts)
        (.then #(js/Promise.all (clj->js (map (fn [v] (layout g v)) variants))))
        (.then (fn [all]
                 (when-not @gone
                   (let [all (vec all)
                         {:keys [index scale]} (pure/choose all variants (.-current kept) aw ah)
                         l (nth all index)]
                     (shown! memory l)
                     (set-laid (assoc l :scale scale))))))
        (.catch #(js/console.error "layout" %)))))

(defn- use-layout!
  "The graph laid out again whenever it or the width changes."
  [{:keys [nodes edges groups]} width memory set-laid]
  (h/use-effect
   (fn []
     (let [gone (atom false)
           g {:nodes (vec nodes) :edges (vec edges) :groups (vec groups)}]
       (if (empty? (:nodes g))
         (do (set-laid nil) (set! (.-current (:kept memory)) nil))
         (lay-out! g memory set-laid gone))
       #(reset! gone true)))
   [nodes edges groups width]))

;; ---- the drawing ----

(defn- drag-start
  "A pointer down on the diagram drags the view until it comes up."
  [view set-view]
  (fn [^js e]
    (let [sx (.-clientX e) sy (.-clientY e) vx (:x view) vy (:y view)
          move (fn [^js m] (set-view #(assoc % :x (+ vx (- (.-clientX m) sx)) :y (+ vy (- (.-clientY m) sy)))))
          up (atom nil)]
      (reset! up (fn []
                   (js/window.removeEventListener "pointermove" move)
                   (js/window.removeEventListener "pointerup" @up)))
      (js/window.addEventListener "pointermove" move)
      (js/window.addEventListener "pointerup" @up))))

(defn- svg-defs [uid vw vh]
  [:defs
   [:pattern {:id (str "grain-" uid) :patternUnits "userSpaceOnUse" :width chalk/grain-size :height chalk/grain-size}
    [:image {:href (chalk/grain-url :fray) :width chalk/grain-size :height chalk/grain-size}]]
   [:mask {:id (str "chalk-" uid) :maskUnits "userSpaceOnUse"
           :x (- (- pad) 40) :y (- (- pad) 40) :width (+ vw 80) :height (+ vh 80)
           :style {:mask-type "alpha"}}
    [:rect {:x (- (- pad) 40) :y (- (- pad) 40) :width (+ vw 80) :height (+ vh 80)
            :fill (str "url(#grain-" uid ")")}]]
   (for [c colors]
     ^{:key c}
     [:marker {:id (str "arrow-" uid "-" c) :viewBox "0 0 10 10" :refX 9 :refY 5
               :markerWidth 7 :markerHeight 7 :orient "auto-start-reverse"}
      [:path {:d "M0,0 L10,5 L0,10 z" :class (str "arrowhead c-" c)}]])])

(defn- group-view [{:keys [x y group] gw :w gh :h}]
  [:g {:class (str "group c-" (pure/known-color colors (:color group) "grey"))
       :style {:transform (str "translate(" x "px, " y "px)")}}
   [:rect.fray {:width gw :height gh :rx 13}]
   [:rect {:width gw :height gh :rx 13}]
   [:text {:x 16 :y 22} (:label group)]])

(defn- edge-label [label edge]
  [:g.edge-label {:style {:transform (str "translate(" (:x label) "px, " (:y label) "px)")}}
   [:rect {:width (:w label) :height (:h label) :rx 5}]
   [:text {:x (/ (:w label) 2) :y (+ 0.5 (/ (:h label) 2)) :text-anchor "middle" :dominant-baseline "middle"}
    (:label edge)]])

(defn- edge-view [{:keys [id d edge label]} uid fresh-ids changed]
  (let [c (pure/known-color colors (:color edge) "grey")
        dash (when (:dashed edge) "5 4")
        css-d (str "path(\"" d "\")")]
    [:g {:class (str "edge c-" c (when (fresh-ids id) " fresh") (when (changed id) " hot"))}
     [:path.fray {:d d :style {:d css-d} :stroke-dasharray dash}]
     [:path {:d d :style {:d css-d} :stroke-dasharray dash
             :marker-end (str "url(#arrow-" uid "-" c ")")}]
     (when (and label (not (str/blank? (:label edge))))
       (edge-label label edge))]))

(defn- drawing
  "The laid-out graph as SVG at its scale."
  [{:keys [laid uid fresh-ids changed seq-no]}]
  (let [{:keys [w h scale]} laid
        vw (+ w (* 2 pad)) vh (+ h (* 2 pad))]
    [:svg {:viewBox (str (- pad) " " (- pad) " " vw " " vh)
           :width (* vw scale) :height (* vh scale)}
     (svg-defs uid vw vh)
     [:g
      (for [g (:groups laid)]
        ^{:key (str "g:" (:id g))} [group-view g])
      (for [e (:edges laid)]
        ^{:key (str "e:" (:id e))} [edge-view e uid fresh-ids changed])
      (for [n (:nodes laid)
            :let [f? (contains? fresh-ids (:id n))]]
        ^{:key (str "n:" (:id n))}
        [node-view n f? (if (and (changed (:id n)) (not f?)) seq-no 0)])]]))

(defn- diagram
  "The diagram box (drag to pan, double click to reset) with its zoomed drawing."
  [{:keys [box layer view set-view uid] :as props}]
  [:div.diagram {:ref #(set! (.-current box) %)
                 :on-pointer-down (drag-start view set-view)
                 :on-double-click #(set-view {:k 1 :x 0 :y 0})
                 :style {"--chalk-mask" (str "url(#chalk-" uid ")")}}
   [:div.zoom-layer {:ref #(set! (.-current layer) %)
                     :style {:transform (str "translate(" (:x view) "px, " (:y view) "px) scale(" (:k view) ")")}}
    (drawing props)]])

(defn graph-view
  "Render with [:f> graph-view block changed seq]: changed = ids (nodes, arrows) the last draw
  changed, seq = that draw's number (a changed node rings once per draw)."
  ([block] (graph-view block #{} 0))
  ([block changed seq-no]
   (let [uid (str/replace (h/use-id) #"[^a-zA-Z0-9]" "")
         box (h/use-ref nil)
         layer (h/use-ref nil)
         [laid set-laid] (h/use-state nil)
         [width set-width] (h/use-state 0)
         [view set-view] (h/use-state {:k 1 :x 0 :y 0})
         known (h/use-ref #{})
         fresh (h/use-ref #{})
         kept (h/use-ref nil)
         memory {:box box :known known :fresh fresh :kept kept}
         ;; Zoom around a point of the diagram box (px from its top-left).
         zoom-at (fn [px py factor]
                   (let [^js l (.-current layer)]
                     (set-view #(pure/zoomed % px py factor (or (some-> l .-offsetLeft) 0) (or (some-> l .-offsetTop) 0)))))]
     (use-wheel-zoom! box zoom-at laid)
     (use-width-steps! box set-width)
     (use-layout! block width memory set-laid)
     (if-not laid
       [:div.diagram {:ref #(set! (.-current box) %)}]
       (diagram {:box box :layer layer :view view :set-view set-view :laid laid :uid uid
                 :fresh-ids (.-current fresh) :changed (set changed) :seq-no seq-no})))))
