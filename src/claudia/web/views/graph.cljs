(ns claudia.web.views.graph
  "A graph block: laid out by ELK (layered), drawn as SVG in chalk. Three layouts are tried (left to
  right, top to bottom, top to bottom with groups stacked) and the one that fits the pane best is
  kept; it changes only when another fits clearly better, so the picture doesn't jump. Nodes and
  arrows move to their new places with CSS transitions; new ones pop in."
  (:require ["elkjs/lib/elk.bundled.js" :as ELK]
            [clojure.string :as str]
            [reagent.hooks :as h]
            [claudia.web.chalk :as chalk]))

(defonce ^:private elk (ELK.))

(def ^:private colors ["green" "red" "amber" "blue" "grey" "violet"])
(def ^:private font "700 15px Kalam, cursive")
(def ^:private group-font "700 14px Kalam, cursive")
(def ^:private note-font "400 12px \"Atkinson Hyperlegible\", system-ui, sans-serif")
(def ^:private note-line 15)
(def ^:private pad 14)

(defonce ^:private ctx (.getContext (js/document.createElement "canvas") "2d"))

(defn- text-w
  ([s] (text-w s font))
  ([s f] (set! (.-font ctx) f) (.-width (.measureText ctx s))))

(defn- wrap
  "Words into lines of at most `max` characters, three lines at most."
  [s max-chars]
  (let [words (remove str/blank? (str/split (str s) #"\s+"))
        [lines cur] (reduce (fn [[lines cur] w]
                              (if (and (seq cur) (> (count (str cur " " w)) max-chars))
                                [(conj lines cur) w]
                                [lines (if (seq cur) (str cur " " w) w)]))
                            [[] ""] words)]
    (vec (take 3 (cond-> lines (seq cur) (conj cur))))))

(defn- node-size [{:keys [shape label id note]}]
  (let [shape (or shape "circle")
        lines (wrap (if (str/blank? label) id label) (if (= shape "circle") 14 18))
        notes (if (str/blank? note) [] (vec (take 2 (wrap note 24))))
        tw (reduce max 0 (map text-w lines))
        nw (reduce max 0 (map #(text-w % note-font) notes))]
    (if (= shape "circle")
      (let [d (max 60 (+ tw 26) (+ 26 (* 16 (count lines))))]
        {:lines lines :notes notes :d d
         :w (max d (if (seq notes) (+ nw 8) 0))
         :h (+ d (if (seq notes) (+ 8 (* note-line (count notes))) 0))})
      (let [w (max 96 (+ tw 32) (+ nw 28))
            h (+ 22 (* 17 (count lines)) (* note-line (count notes)))]
        (if (= shape "diamond")
          {:lines lines :notes notes :d 0 :w (* w 1.45) :h (* h 1.7)}
          {:lines lines :notes notes :d 0 :w w :h h})))))

(defn- round-path
  "Points -> an SVG path with rounded bends."
  [pts]
  (when (>= (count pts) 2)
    (let [[p0] pts
          mid (for [i (range 1 (dec (count pts)))]
                (let [{px :x py :y} (nth pts (dec i))
                      {cx :x cy :y} (nth pts i)
                      {nx :x ny :y} (nth pts (inc i))
                      d1 (js/Math.hypot (- cx px) (- cy py))
                      d2 (js/Math.hypot (- nx cx) (- ny cy))
                      k (min 9 (/ d1 2) (/ d2 2))
                      ax (+ cx (* (/ (- px cx) d1) k)) ay (+ cy (* (/ (- py cy) d1) k))
                      bx (+ cx (* (/ (- nx cx) d2) k)) by (+ cy (* (/ (- ny cy) d2) k))]
                  (str " L" ax "," ay " Q" cx "," cy " " bx "," by)))
          {lx :x ly :y} (peek pts)]
      (str "M" (:x p0) "," (:y p0) (apply str mid) " L" lx "," ly))))

(defn- eid [{:keys [from to]}] (str from "->" to))

(defn- stack-edges
  "Hidden arrows between a group's unconnected members, so top-to-bottom layouts stack them in a
  column instead of one wide row."
  [{:keys [nodes edges groups]}]
  (let [linked (set (mapcat (fn [{:keys [from to]}] [[from to] [to from]]) edges))]
    (for [g groups
          :let [members (filterv #(= (:group %) (:id g)) nodes)]
          [a b] (partition 2 1 (map :id members))
          :when (not (linked [a b]))]
      {:id (str "~" a "~" b) :sources [a] :targets [b]})))

(defn- layout
  "A promise of the laid-out graph for one variant (:right :down :down-stacked)."
  [{:keys [nodes edges groups] :as g} variant]
  (let [direction (if (= variant :right) "RIGHT" "DOWN")
        sizes (into {} (map (juxt :id node-size)) nodes)
        group-ids (set (map :id groups))
        node-ids (set (map :id nodes))
        leaf (fn [n] {:id (:id n) :width (get-in sizes [(:id n) :w]) :height (get-in sizes [(:id n) :h])})
        min-size (fn [w h] (if (= direction "DOWN") (str "(" h ", " w ")") (str "(" w ", " h ")")))
        children (concat
                  (for [grp groups
                        :let [kids (filter #(= (:group %) (:id grp)) nodes)]
                        :when (seq kids)]
                    {:id (:id grp)
                     :layoutOptions {"elk.padding" "[top=36,left=18,bottom=18,right=18]"
                                     "elk.nodeSize.constraints" "MINIMUM_SIZE"
                                     "elk.nodeSize.minimum" (min-size (js/Math.ceil (+ 34 (text-w (or (:label grp) "") group-font))) 60)}
                     :children (map leaf kids)})
                  (map leaf (remove #(and (:group %) (group-ids (:group %))) nodes)))
        edges (filter #(and (node-ids (:from %)) (node-ids (:to %))) edges)
        graph {:id "root"
               :layoutOptions {"elk.algorithm" "layered"
                               "elk.direction" direction
                               "elk.hierarchyHandling" "INCLUDE_CHILDREN"
                               "elk.edgeRouting" "ORTHOGONAL"
                               "elk.layered.spacing.nodeNodeBetweenLayers" "48"
                               "elk.spacing.nodeNode" "22"
                               "elk.spacing.edgeLabel" "4"
                               "elk.edgeLabels.inline" "true"
                               "elk.layered.considerModelOrder.strategy" "NODES_AND_EDGES"
                               "elk.layered.nodePlacement.strategy" "BRANDES_KOEPF"
                               "elk.padding" "[top=12,left=12,bottom=12,right=12]"}
               :children children
               :edges (concat
                       (for [e edges]
                         {:id (eid e) :sources [(:from e)] :targets [(:to e)]
                          :labels (if (str/blank? (:label e)) []
                                      [{:text (:label e) :width (+ 12 (text-w (:label e) note-font)) :height 18}])})
                       (when (= variant :down-stacked) (stack-edges g)))}
        by-id (into {} (map (juxt :id identity)) nodes)
        group-by-id (into {} (map (juxt :id identity)) groups)
        edge-by-id (into {} (map (juxt eid identity)) edges)]
    (-> (.layout elk (clj->js graph))
        (.then
         (fn [^js out]
           (let [laid-node (fn [^js k ox oy]
                             (let [id (.-id k) s (sizes id)]
                               (merge s {:id id :x (+ ox (.-x k)) :y (+ oy (.-y k))
                                         :w (.-width k) :h (.-height k) :node (by-id id)})))
                 offsets (volatile! {"root" [0 0]})
                 lg (volatile! []) ln (volatile! [])]
             (doseq [^js c (or (.-children out) #js [])]
               (if-let [grp (group-by-id (.-id c))]
                 (do (vswap! offsets assoc (.-id c) [(.-x c) (.-y c)])
                     (vswap! lg conj {:id (.-id c) :x (.-x c) :y (.-y c) :w (.-width c) :h (.-height c) :group grp})
                     (doseq [k (or (.-children c) #js [])]
                       (vswap! ln conj (laid-node k (.-x c) (.-y c)))))
                 (vswap! ln conj (laid-node c 0 0))))
             {:w (or (.-width out) 0) :h (or (.-height out) 0) :variant variant
              :groups @lg :nodes @ln
              :edges (vec (for [^js e (or (.-edges out) #js [])
                                :let [edge (edge-by-id (.-id e))
                                      ^js sec (some-> (.-sections e) (aget 0))]
                                :when (and edge sec)
                                :let [[ox oy] (get @offsets (or (.-container e) "root") [0 0])
                                      pts (mapv (fn [^js p] {:x (+ ox (.-x p)) :y (+ oy (.-y p))})
                                                (concat [(.-startPoint sec)] (or (.-bendPoints sec) []) [(.-endPoint sec)]))
                                      ^js lab (some-> (.-labels e) (aget 0))]]
                            {:id (.-id e) :d (round-path pts) :edge edge
                             :label (when lab {:x (+ ox (.-x lab)) :y (+ oy (.-y lab)) :w (.-width lab) :h (.-height lab)})}))}))))))

(defn- fit-of
  "How much a layout must scale to fit: the width, and up to twice the pane's visible height (the
  right side scrolls)."
  [{:keys [w h]} avail-w avail-h]
  (min (/ avail-w (+ w (* 2 pad))) (/ (* 2 avail-h) (+ h (* 2 pad)))))

(def ^:private variants [:right :down :down-stacked])

(defn- node-view [{:keys [x y w h d lines notes node]} fresh? pulse]
  (let [shape (or (:shape node) "circle")
        circle? (= shape "circle")
        cx (/ w 2)
        cy (if circle? (/ d 2) (/ h 2))
        text-top (- cy (/ (* 16 (dec (count lines))) 2) (if circle? 0 (/ (* note-line (count notes)) 2)))
        draw (fn [cls]
               (case shape
                 "circle" [:circle {:class cls :cx cx :cy cy :r (/ d 2)}]
                 "diamond" [:polygon {:class cls :points (str cx ",1 " (dec w) "," cy " " cx "," (dec h) " 1," cy)}]
                 [:rect {:class cls :x 0.75 :y 0.75 :width (- w 1.5) :height (- h 1.5)
                         :rx (if (= shape "pill") (/ h 2) 9)}]))
        bx (if circle? (+ cx (* d 0.36)) (- w 4))
        by (if circle? (- cy (* d 0.36)) 4)
        color (if (some #{(:color node)} colors) (:color node) "blue")]
    [:g {:class (str "node c-" color " s-" (or (:state node) "none") (when fresh? " fresh"))
         :style {:transform (str "translate(" x "px, " y "px)")}}
     ;; A changed node rings three times; a new key per draw starts it again.
     (when (pos? pulse)
       (if circle?
         ^{:key pulse} [:circle.ring {:cx cx :cy cy :r (+ 5 (/ d 2))}]
         ^{:key pulse} [:rect.ring {:x -5 :y -5 :width (+ w 10) :height (+ h 10) :rx (if (= shape "pill") (+ 5 (/ h 2)) 13)}]))
     (draw "fray")
     (draw "body")
     (into [:text.label {:x cx :y text-top :text-anchor "middle" :dominant-baseline "middle"}]
           (map-indexed (fn [i l] [:tspan {:key i :x cx :dy (if (pos? i) 16 0)} l]) lines))
     (when (seq notes)
       (into [:text.note {:x cx :y (if circle? (+ d 15) (+ text-top (* 16 (dec (count lines))) 17))
                          :text-anchor "middle" :dominant-baseline "middle"}]
             (map-indexed (fn [i l] [:tspan {:key i :x cx :dy (if (pos? i) note-line 0)} l]) notes)))
     (case (:state node)
       "done" [:g.badge.done {:transform (str "translate(" bx "," by ")")}
               [:circle {:r 8}] [:path {:d "M-3.5,0 L-1,2.6 L3.8,-2.6"}]]
       "blocked" [:g.badge.blocked {:transform (str "translate(" bx "," by ")")}
                  [:circle {:r 8}] [:path {:d "M-3,-3 L3,3 M3,-3 L-3,3"}]]
       nil)]))

(defn- avail-size
  "The room for a graph: its block's width (or the scroller's, less its padding) and 80% of the
  scroller's height."
  [^js el]
  (let [^js stage (some-> el (.closest ".right-scroll"))
        w (let [cw (some-> el .-clientWidth)] (if (pos? cw) cw (- (or (some-> stage .-clientWidth) 800) 36)))]
    [w (max 320 (* 0.8 (or (some-> stage .-clientHeight) 700)))]))

(defn graph-view
  "Render with [:f> graph-view block changed seq]: changed = ids (nodes, arrows) the last draw
  changed, seq = that draw's number (a changed node rings once per draw)."
  ([block] (graph-view block #{} 0))
  ([{:keys [nodes edges groups]} changed seq-no]
   (let [uid (str/replace (h/use-id) #"[^a-zA-Z0-9]" "")
         box (h/use-ref nil)
         layer (h/use-ref nil)
         [laid set-laid] (h/use-state nil)
         [width set-width] (h/use-state 0)
         [view set-view] (h/use-state {:k 1 :x 0 :y 0})
         known (h/use-ref #{})
         fresh (h/use-ref #{})
         kept (h/use-ref nil)
         changed (set changed)
         ;; Zoom around a point of the diagram box (px from its top-left).
         zoom-at (fn [px py factor]
                   (set-view (fn [{:keys [k x y]}]
                               (let [k2 (min 4 (max 0.3 (* k factor)))
                                     ^js l (.-current layer)
                                     lx (or (some-> l .-offsetLeft) 0) ly (or (some-> l .-offsetTop) 0)
                                     ux (/ (- px lx x) k) uy (/ (- py ly y) k)]
                                 {:k k2 :x (- px lx (* ux k2)) :y (- py ly (* uy k2))}))))]
     ;; Ctrl+wheel and trackpad pinch zoom the picture, not the page: a native listener, as React's
     ;; wheel listener is passive and can't stop the browser's zoom.
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
      [laid])
     ;; Lay out again when the block's width changes noticeably (window resize, divider drag).
     (h/use-effect
      (fn []
        (let [t (atom nil)
              ro (js/ResizeObserver.
                  (fn [entries]
                    (let [w (.. (aget entries 0) -contentRect -width)]
                      (js/clearTimeout @t)
                      (reset! t (js/setTimeout #(set-width (fn [old] (if (> (js/Math.abs (- w old)) 40) w old))) 150)))))]
          (.observe ro (.-current box))
          #(do (.disconnect ro) (js/clearTimeout @t))))
      [])
     (h/use-effect
      (fn []
        (let [gone (atom false)
              g {:nodes (vec nodes) :edges (vec edges) :groups (vec groups)}]
          (if (empty? (:nodes g))
            (do (set-laid nil) (set! (.-current kept) nil))
            (let [[aw ah] (avail-size (.-current box))]
              (-> (.-ready js/document.fonts)
                  (.then #(js/Promise.all (clj->js (map (fn [v] (layout g v)) variants))))
                  (.then (fn [all]
                           (when-not @gone
                             (let [all (vec all)
                                   ;; The layout that fits the pane best; another only when it fits
                                   ;; clearly better, so the picture doesn't jump.
                                   fits (mapv #(fit-of % aw ah) all)
                                   top (apply max fits)
                                   best (first (keep-indexed #(when (= %2 top) %1) fits))
                                   k (.-current kept)
                                   ki (when k (first (keep-indexed #(when (= %2 k) %1) variants)))
                                   best (if (and ki (< (fits best) (* 1.25 (fits ki)))) ki best)
                                   l (nth all best)
                                   ids (set (concat (map :id (:nodes l)) (map :id (:edges l))))]
                               (set! (.-current kept) (:variant l))
                               (set! (.-current fresh) (into #{} (remove (.-current known)) ids))
                               (set! (.-current known) ids)
                               (set-laid (assoc l :scale (max 0.5 (min 1.2 (fits best)))))))))
                  (.catch #(js/console.error "layout" %)))))
          #(reset! gone true)))
      [nodes edges groups width])
     (if-not laid
       [:div.diagram {:ref #(set! (.-current box) %)}]
       (let [{:keys [w h scale]} laid
             vw (+ w (* 2 pad)) vh (+ h (* 2 pad))
             fresh-ids (.-current fresh)
             on-down (fn [^js e]
                       (let [sx (.-clientX e) sy (.-clientY e) vx (:x view) vy (:y view)
                             move (fn [^js m] (set-view #(assoc % :x (+ vx (- (.-clientX m) sx)) :y (+ vy (- (.-clientY m) sy)))))
                             up (atom nil)]
                         (reset! up (fn []
                                      (js/window.removeEventListener "pointermove" move)
                                      (js/window.removeEventListener "pointerup" @up)))
                         (js/window.addEventListener "pointermove" move)
                         (js/window.addEventListener "pointerup" @up)))]
         [:div.diagram {:ref #(set! (.-current box) %)
                        :on-pointer-down on-down
                        :on-double-click #(set-view {:k 1 :x 0 :y 0})
                        :style {"--chalk-mask" (str "url(#chalk-" uid ")")}}
          [:div.zoom-layer {:ref #(set! (.-current layer) %)
                            :style {:transform (str "translate(" (:x view) "px, " (:y view) "px) scale(" (:k view) ")")}}
           [:svg {:viewBox (str (- pad) " " (- pad) " " vw " " vh)
                  :width (* vw scale) :height (* vh scale)}
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
                [:path {:d "M0,0 L10,5 L0,10 z" :class (str "arrowhead c-" c)}]])]
            [:g
             (for [{:keys [id x y group] gw :w gh :h} (:groups laid)]
               ^{:key (str "g:" id)}
               [:g {:class (str "group c-" (if (some #{(:color group)} colors) (:color group) "grey"))
                    :style {:transform (str "translate(" x "px, " y "px)")}}
                [:rect.fray {:width gw :height gh :rx 13}]
                [:rect {:width gw :height gh :rx 13}]
                [:text {:x 16 :y 22} (:label group)]])
             (for [{:keys [id d edge label]} (:edges laid)
                   :let [c (if (some #{(:color edge)} colors) (:color edge) "grey")
                         dash (when (:dashed edge) "5 4")
                         css-d (str "path(\"" d "\")")]]
               ^{:key (str "e:" id)}
               [:g {:class (str "edge c-" c (when (fresh-ids id) " fresh") (when (changed id) " hot"))}
                [:path.fray {:d d :style {:d css-d} :stroke-dasharray dash}]
                [:path {:d d :style {:d css-d} :stroke-dasharray dash
                        :marker-end (str "url(#arrow-" uid "-" c ")")}]
                (when (and label (not (str/blank? (:label edge))))
                  [:g.edge-label {:style {:transform (str "translate(" (:x label) "px, " (:y label) "px)")}}
                   [:rect {:width (:w label) :height (:h label) :rx 5}]
                   [:text {:x (/ (:w label) 2) :y (+ 0.5 (/ (:h label) 2)) :text-anchor "middle" :dominant-baseline "middle"}
                    (:label edge)]])])
             (for [n (:nodes laid)
                   :let [f? (contains? fresh-ids (:id n))]]
               ^{:key (str "n:" (:id n))}
               [node-view n f? (if (and (changed (:id n)) (not f?)) seq-no 0)])]]]])))))
