(ns claudia.web.scene3d.labels
  "Labels that would sit on each other move apart, up or down, easing there frame by frame. Pure:
  screen rects in, each label's shift out; the engine measures and moves the elements.

  A rect: {:left :right :top :bottom} as measured (with the label's current shift in it). A shift:
  {:y (where it is now) :to (where it goes) :side (-1 up, 1 down, 0 not moved)}.")

(def ^:private still {:y 0 :to 0 :side 0})

(defn- overlaps? [{:keys [left right]} top bottom placed]
  (and (< left (:right placed)) (> right (:left placed))
       (< top (:bottom placed)) (> bottom (:top placed))))

(defn- side-of
  "Which way a label moves off the one it hits: away from that one's middle (once chosen, kept)."
  [side top bottom hit]
  (if (zero? side) (if (< (+ top bottom) (+ (:top hit) (:bottom hit))) -1 1) side))

(defn clear-of
  "How far (dy) a label from top to bottom moves, and which way, so it sits on none of `placed`;
  at most 10 moves."
  [r top bottom side placed]
  (loop [guard 0 dy 0 side side]
    (let [hit (when (< guard 10) (some #(when (overlaps? r (+ top dy) (+ bottom dy) %) %) placed))]
      (if-not hit
        [dy side]
        (let [side (side-of side top bottom hit)]
          (recur (inc guard)
                 (if (pos? side) (+ (- (:bottom hit) top) 3) (- (:top hit) bottom 3))
                 side))))))

(defn- place-one [{:keys [placed shifts]} [r s]]
  (let [s (or s still)
        top (- (:top r) (:y s))
        bottom (- (:bottom r) (:y s))
        [dy side] (clear-of r top bottom (:side s) placed)
        to (max -90 (min 90 dy))]
    {:shifts (conj shifts (assoc s :to to :side (if (zero? dy) 0 side)))
     :placed (conj placed {:left (:left r) :right (:right r) :top (+ top to) :bottom (+ bottom to)})}))

(defn targets
  "Where each label goes: rects and their shifts (nil for a new label), in order; each is placed
  clear of the ones before it. -> the shifts with :to and :side set."
  [rects shifts]
  (:shifts (reduce place-one {:placed [] :shifts []} (map vector rects shifts))))

(defn ease
  "One frame of a shift moving to its target: snaps there when under half a pixel away."
  [{:keys [y to] :as s}]
  (assoc s :y (if (< (js/Math.abs (- to y)) 0.5) to (+ y (* 0.25 (- to y))))))

(defn easing? "Whether a shift is still on its way (before this frame's step)." [{:keys [y to]}]
  (>= (js/Math.abs (- to y)) 0.5))

(defn translate "The CSS translate for a shift of y px." [y]
  (if (zero? y) "" (str "0 " (.toFixed y 1) "px")))
