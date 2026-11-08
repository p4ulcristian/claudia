(ns claudia.web.scene3d.labels
  "Labels as callouts, like the plates of an atlas: never on the model, but in a column on each side
  of it, each joined by a thin line to a dot on its part. Pure: which labels show, which side each
  goes to, where in its column it stands, and how it eases there; the engine projects the anchors
  and claudia.web.scene3d.callouts moves the elements and draws the lines.

  An item: {:id :x :y (the anchor's screen point) :w :h (the label's size)}. The model's rect:
  {:left :right :top :bottom} in screen pixels.")

(def margin "Space between the model and a column, and between a column and the stage's edge." 18)
(def gap "Space between two labels in a column." 6)
(def elbow "The leader's last straight stretch, into the label." 14)

(defn wanted?
  "Whether a label shows. Without a story: every label. In a story: the parts the step focuses on,
  else the ones it brings in, else (an overview step) every label; and always the part under the
  pointer."
  [{:keys [story? focus shows hover]} id]
  (boolean (or (not story?) (= hover id) (contains? focus id)
               (and (empty? focus) (or (empty? shows) (contains? shows id))))))

(defn side-of
  "Which column an anchor at x goes to: the side of the model's middle it is on."
  [x {:keys [left right]}]
  (if (< x (/ (+ left right) 2)) :left :right))

(defn stack
  "The centres of a column's labels, wanted at ys with heights hs (top to bottom), so that none
  overlaps the next (gap apart) and all stay between top and bottom when they fit; when they do
  not, they stack down from the top (as many as fit stay inside). -> centres."
  [ys hs top bottom]
  (let [down (reduce (fn [acc [y h]]
                       (let [prev (peek acc)
                             floor (if prev (+ (:end prev) gap) top)]
                         (conj acc (let [c (max y (+ floor (/ h 2)))] {:c c :end (+ c (/ h 2))}))))
                     [] (map vector ys hs))
        up (reduce (fn [acc [{:keys [c]} h]]
                     (let [ceiling (if-let [nxt (first acc)] (- (:start nxt) gap) bottom)
                           c2 (min c (- ceiling (/ h 2)))]
                       (cons {:c c2 :start (- c2 (/ h 2))} acc)))
                   () (reverse (map vector down hs)))]
    (if (and (seq up) (< (:start (first up)) top))
      (vec (reductions (fn [c [h0 h1]] (+ c (/ h0 2) gap (/ h1 2))) (+ top (/ (first hs) 2)) (partition 2 1 hs)))
      (mapv :c up))))

(defn columns
  "Where the columns stand: the left column's right edge and the right column's left edge, clear of
  the model when the stage has room, else at the stage's edges. widest: the widest label on each
  side {:left :right}; w: the stage's width."
  [{:keys [left right]} w widest]
  {:left-x (max (+ margin (:left widest 0)) (- left margin))
   :right-x (min (- w margin (:right widest 0)) (+ right margin))})

(defn- column
  "A column's labels with their centres; those that do not fit above its bottom are left out."
  [items {:keys [top bottom]}]
  (let [sorted (sort-by :y items)]
    (filter #(<= (+ (:cy %) (/ (:h %) 2)) (+ bottom 0.5))
            (map (fn [it c] (assoc it :cy c)) sorted (stack (map :y sorted) (map :h sorted) top bottom)))))

(defn span
  "How far down a column from x0 to x1 may reach on a stage h tall: below what covers its top half
  (the legend), above what covers its bottom half (the subtitles, the bar). obstacles: screen rects.
  -> {:top :bottom}."
  [x0 x1 h obstacles]
  (let [hit (filter #(and (< (:left %) x1) (> (:right %) x0)) obstacles)
        upper (filter #(< (+ (:top %) (:bottom %)) h) hit)
        lower (remove (set upper) hit)]
    {:top (reduce max margin (map #(+ (:bottom %) gap) upper))
     :bottom (reduce min (- h margin) (map #(- (:top %) gap) lower))}))

(defn- need [items] (+ (reduce + (map :h items)) (* gap (max 0 (dec (count items))))))

(defn balance
  "Labels moved to the other column while one column has more than fits (room: {:left px :right
  px}) and the other has space; the one moved is the nearest the middle. -> {:left items :right
  items}."
  [{:keys [left right] :as sides} room]
  (let [over? (fn [s] (> (need (s sides)) (s room)))
        fits? (fn [s it] (<= (need (conj (vec (s sides)) it)) (s room)))
        move (fn [from to pick] (let [it (pick :x (from sides))]
                                  (when (and it (fits? to it))
                                    (balance (-> sides (update from #(remove #{it} %)) (update to conj it)) room))))]
    (or (when (over? :left) (move :left :right #(apply max-key %1 %2)))
        (when (over? :right) (move :right :left #(apply min-key %1 %2)))
        {:left (vec left) :right (vec right)})))

(defn layout
  "Each shown label's place: items (anchors and sizes), the model's rect, the stage's w and h, and
  the rects labels must keep clear of (legend, subtitles, bar).
  -> {id {:side :left :top :cy}}: the label's top-left corner and its middle's height (a label
  that does not fit is missing)."
  [items rect w h obstacles]
  (let [first-cut (group-by #(side-of (:x %) rect) items)
        widest-of (fn [g] {:left (reduce max 0 (map :w (:left g))) :right (reduce max 0 (map :w (:right g)))})
        cols0 (columns rect w (widest-of first-cut))
        room (fn [x0 x1] (let [{:keys [top bottom]} (span x0 x1 h obstacles)] (- bottom top)))
        by-side (balance first-cut {:left (room (- (:left-x cols0) 200) (:left-x cols0))
                                    :right (room (:right-x cols0) (+ (:right-x cols0) 200))})
        widest (widest-of by-side)
        {:keys [left-x right-x]} (columns rect w widest)
        place (fn [s it] [(:id it) {:side s :cy (:cy it) :top (- (:cy it) (/ (:h it) 2))
                                    :left (if (= s :left) (- left-x (:w it)) right-x)}])]
    (into {} (concat (map #(place :left %) (column (:left by-side) (span (- left-x (:left widest)) left-x h obstacles)))
                     (map #(place :right %) (column (:right by-side) (span right-x (+ right-x (:right widest)) h obstacles)))))))

(defn leader
  "The line from an anchor to its label: [anchor elbow end] points, the end at the label's edge
  facing the model."
  [{:keys [x y]} {:keys [side left cy]} w-label]
  (let [edge (if (= side :left) (+ left w-label) left)
        bend (if (= side :left) (+ edge elbow) (- edge elbow))]
    [[x y] [bend cy] [edge cy]]))

(defn ease
  "One frame of a label's height moving from y to its target: snaps there when under half a pixel
  away. -> the new y."
  [y to]
  (if (or (nil? y) (< (js/Math.abs (- to y)) 0.5)) to (+ y (* 0.3 (- to y)))))
