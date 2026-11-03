(ns claudia.web.views.pure.graph
  "A graph block's layout as plain data: node sizes (with a text-measuring function passed in), the
  graph handed to ELK, ELK's answer turned into what is drawn, which of the tried layouts is kept, the
  arrows' rounded paths, zooming."
  (:require [clojure.string :as str]))

(def note-line 15)
(def pad 14)

(defn wrap
  "Words into lines of at most max-chars characters, three lines at most."
  [s max-chars]
  (let [words (remove str/blank? (str/split (str s) #"\s+"))
        [lines cur] (reduce (fn [[lines cur] w]
                              (if (and (seq cur) (> (count (str cur " " w)) max-chars))
                                [(conj lines cur) w]
                                [lines (if (seq cur) (str cur " " w) w)]))
                            [[] ""] words)]
    (vec (take 3 (cond-> lines (seq cur) (conj cur))))))

(defn- circle-size [lines notes tw nw]
  (let [d (max 60 (+ tw 26) (+ 26 (* 16 (count lines))))]
    {:lines lines :notes notes :d d
     :w (max d (if (seq notes) (+ nw 8) 0))
     :h (+ d (if (seq notes) (+ 8 (* note-line (count notes))) 0))}))

(defn- box-size [shape lines notes tw nw]
  (let [w (max 96 (+ tw 32) (+ nw 28))
        h (+ 22 (* 17 (count lines)) (* note-line (count notes)))]
    (if (= shape "diamond")
      {:lines lines :notes notes :d 0 :w (* w 1.45) :h (* h 1.7)}
      {:lines lines :notes notes :d 0 :w w :h h})))

(defn node-size
  "A node's lines, notes and size. label-w and note-w give a text's width in px."
  [{:keys [shape label id note]} label-w note-w]
  (let [shape (or shape "circle")
        lines (wrap (if (str/blank? label) id label) (if (= shape "circle") 14 18))
        notes (if (str/blank? note) [] (vec (take 2 (wrap note 24))))
        tw (reduce max 0 (map label-w lines))
        nw (reduce max 0 (map note-w notes))]
    (if (= shape "circle")
      (circle-size lines notes tw nw)
      (box-size shape lines notes tw nw))))

(defn- bend
  "The rounded corner at point i of pts."
  [pts i]
  (let [{px :x py :y} (nth pts (dec i))
        {cx :x cy :y} (nth pts i)
        {nx :x ny :y} (nth pts (inc i))
        d1 (js/Math.hypot (- cx px) (- cy py))
        d2 (js/Math.hypot (- nx cx) (- ny cy))
        k (min 9 (/ d1 2) (/ d2 2))
        ax (+ cx (* (/ (- px cx) d1) k)) ay (+ cy (* (/ (- py cy) d1) k))
        bx (+ cx (* (/ (- nx cx) d2) k)) by (+ cy (* (/ (- ny cy) d2) k))]
    (str " L" ax "," ay " Q" cx "," cy " " bx "," by)))

(defn round-path
  "Points -> an SVG path with rounded bends."
  [pts]
  (when (>= (count pts) 2)
    (let [[p0] pts
          {lx :x ly :y} (peek pts)]
      (str "M" (:x p0) "," (:y p0) (apply str (map #(bend pts %) (range 1 (dec (count pts))))) " L" lx "," ly))))

(defn eid [{:keys [from to]}] (str from "->" to))

(defn stack-edges
  "Hidden arrows between a group's unconnected members, so top-to-bottom layouts stack them in a
  column instead of one wide row."
  [{:keys [nodes edges groups]}]
  (let [linked (set (mapcat (fn [{:keys [from to]}] [[from to] [to from]]) edges))]
    (for [g groups
          :let [members (filterv #(= (:group %) (:id g)) nodes)]
          [a b] (partition 2 1 (map :id members))
          :when (not (linked [a b]))]
      {:id (str "~" a "~" b) :sources [a] :targets [b]})))

(def ^:private root-options
  {"elk.algorithm" "layered"
   "elk.hierarchyHandling" "INCLUDE_CHILDREN"
   "elk.edgeRouting" "ORTHOGONAL"
   "elk.layered.spacing.nodeNodeBetweenLayers" "48"
   "elk.spacing.nodeNode" "22"
   "elk.spacing.edgeLabel" "4"
   "elk.edgeLabels.inline" "true"
   "elk.layered.considerModelOrder.strategy" "NODES_AND_EDGES"
   "elk.layered.nodePlacement.strategy" "BRANDES_KOEPF"
   "elk.padding" "[top=12,left=12,bottom=12,right=12]"})

(defn- leaf [sizes n]
  {:id (:id n) :width (get-in sizes [(:id n) :w]) :height (get-in sizes [(:id n) :h])})

(defn- group-child
  "A group as an ELK node holding its members, at least as wide as its label (group-w)."
  [direction sizes grp kids group-w]
  (let [w (js/Math.ceil (+ 34 (group-w (or (:label grp) ""))))
        h 60]
    {:id (:id grp)
     :layoutOptions {"elk.padding" "[top=36,left=18,bottom=18,right=18]"
                     "elk.nodeSize.constraints" "MINIMUM_SIZE"
                     "elk.nodeSize.minimum" (if (= direction "DOWN") (str "(" h ", " w ")") (str "(" w ", " h ")"))}
     :children (map #(leaf sizes %) kids)}))

(defn- elk-edge [e note-w]
  {:id (eid e) :sources [(:from e)] :targets [(:to e)]
   :labels (if (str/blank? (:label e)) []
               [{:text (:label e) :width (+ 12 (note-w (:label e))) :height 18}])})

(defn shown-edges
  "The arrows whose both ends are nodes of the graph."
  [{:keys [nodes edges]}]
  (let [node-ids (set (map :id nodes))]
    (filter #(and (node-ids (:from %)) (node-ids (:to %))) edges)))

(defn elk-graph
  "The graph as ELK takes it for one variant (:right :down :down-stacked). sizes: node id -> its
  size; group-w and note-w give a text's width in px."
  [{:keys [nodes groups] :as g} variant sizes group-w note-w]
  (let [direction (if (= variant :right) "RIGHT" "DOWN")
        group-ids (set (map :id groups))]
    {:id "root"
     :layoutOptions (assoc root-options "elk.direction" direction)
     :children (concat
                (for [grp groups
                      :let [kids (filter #(= (:group %) (:id grp)) nodes)]
                      :when (seq kids)]
                  (group-child direction sizes grp kids group-w))
                (map #(leaf sizes %) (remove #(and (:group %) (group-ids (:group %))) nodes)))
     :edges (concat (map #(elk-edge % note-w) (shown-edges g))
                    (when (= variant :down-stacked) (stack-edges g)))}))

(defn- by [f xs] (into {} (map (juxt f identity)) xs))

(defn- laid-node [sizes nodes-by-id k ox oy]
  (let [id (:id k)]
    (merge (sizes id) {:id id :x (+ ox (:x k)) :y (+ oy (:y k))
                       :w (:width k) :h (:height k) :node (nodes-by-id id)})))

(defn- laid-children
  "ELK's top-level children as the groups and nodes drawn, and each group's offset."
  [out sizes {:keys [nodes groups]}]
  (let [groups-by-id (by :id groups)
        nodes-by-id (by :id nodes)]
    (reduce (fn [acc c]
              (if-let [grp (groups-by-id (:id c))]
                (-> acc
                    (assoc-in [:offsets (:id c)] [(:x c) (:y c)])
                    (update :groups conj {:id (:id c) :x (:x c) :y (:y c) :w (:width c) :h (:height c) :group grp})
                    (update :nodes into (map #(laid-node sizes nodes-by-id % (:x c) (:y c)) (:children c))))
                (update acc :nodes conj (laid-node sizes nodes-by-id c 0 0))))
            {:offsets {"root" [0 0]} :groups [] :nodes []}
            (:children out))))

(defn- laid-edge [offsets edge e]
  (let [sec (first (:sections e))
        [ox oy] (get offsets (or (:container e) "root") [0 0])
        pts (mapv (fn [p] {:x (+ ox (:x p)) :y (+ oy (:y p))})
                  (concat [(:startPoint sec)] (:bendPoints sec) [(:endPoint sec)]))
        lab (first (:labels e))]
    {:id (:id e) :d (round-path pts) :edge edge
     :label (when lab {:x (+ ox (:x lab)) :y (+ oy (:y lab)) :w (:width lab) :h (:height lab)})}))

(defn laid-out
  "ELK's answer (as data, keyword keys) as what is drawn: the size, the groups, the nodes (with their
  lines) and the arrows (with their paths), all in the root's coordinates."
  [out variant sizes g]
  (let [{:keys [offsets groups nodes]} (laid-children out sizes g)
        edges-by-id (by eid (shown-edges g))]
    {:w (or (:width out) 0) :h (or (:height out) 0) :variant variant
     :groups groups :nodes nodes
     :edges (vec (for [e (:edges out)
                       :let [edge (edges-by-id (:id e))]
                       :when (and edge (first (:sections e)))]
                   (laid-edge offsets edge e)))}))

(defn fit-of
  "How much a layout must scale to fit: the width, and up to twice the pane's visible height (the
  right side scrolls)."
  [{:keys [w h]} avail-w avail-h]
  (min (/ avail-w (+ w (* 2 pad))) (/ (* 2 avail-h) (+ h (* 2 pad)))))

(defn choose
  "Which of the layouts (one per variant, in order) to show, given the room: the one that fits best;
  the one shown before (kept, a variant) while the best fits less than 25% better. Its index and
  its scale."
  [layouts variants kept avail-w avail-h]
  (let [fits (mapv #(fit-of % avail-w avail-h) layouts)
        top (apply max fits)
        best (first (keep-indexed #(when (= %2 top) %1) fits))
        ki (when kept (first (keep-indexed #(when (= %2 kept) %1) variants)))
        best (if (and ki (< (fits best) (* 1.25 (fits ki)))) ki best)]
    {:index best :scale (max 0.5 (min 1.2 (fits best)))}))

(defn zoomed
  "The view {k x y} zoomed by factor around point px py of the diagram box, its layer at lx ly."
  [{:keys [k x y]} px py factor lx ly]
  (let [k2 (min 4 (max 0.3 (* k factor)))
        ux (/ (- px lx x) k) uy (/ (- py ly y) k)]
    {:k k2 :x (- px lx (* ux k2)) :y (- py ly (* uy k2))}))

(defn ids-of
  "Every node and arrow id of a laid-out graph."
  [laid]
  (set (concat (map :id (:nodes laid)) (map :id (:edges laid)))))

(defn known-color
  "c when it is one of colors, else fallback."
  [colors c fallback]
  (if (some #{c} colors) c fallback))
