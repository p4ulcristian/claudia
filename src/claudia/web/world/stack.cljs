(ns claudia.web.world.stack
  "Screens that would cover each other on the page are moved apart, like name plates in a game: in
  order of distance (the nearest stays put), each screen moves up, left or right, whichever is the
  shortest way clear of every screen placed before it. Boxes are in page pixels with y up:
  {:x :y :hw :hh} (centre, half width, half height); a move is [dx dy].")

(def ^:private up-cost
  "Moving up counts a little less than sideways: screens stack over their robots first."
  0.8)

(defn- overlaps? [{:keys [x y hw hh]} [dx dy] placed gap]
  (and (< (js/Math.abs (- (+ x dx) (:x placed))) (+ hw (:hw placed) gap))
       (< (js/Math.abs (- (+ y dy) (:y placed))) (+ hh (:hh placed) gap))))

(defn- step
  "The move one push further in dir clears hits."
  [{:keys [x y hw hh]} [dx dy] hits gap dir]
  (case dir
    :up [dx (- (apply max (map #(+ (:y %) (:hh %) gap hh) hits)) y)]
    :left [(- (apply min (map #(- (:x %) (:hw %) gap hw) hits)) x) dy]
    :right [(- (apply max (map #(+ (:x %) (:hw %) gap hw) hits)) x) dy]))

(defn- push
  "The move that takes box clear of placed, pushing only in dir."
  [box placed gap dir]
  (loop [mv [0 0] n 0]
    (let [hits (filter #(overlaps? box mv % gap) placed)]
      (if (or (empty? hits) (> n (count placed)))
        mv
        (recur (step box mv hits gap dir) (inc n))))))

(defn- cost [[dx dy]] (+ (js/Math.abs dx) (* up-cost (js/Math.abs dy))))

(defn moves
  "Each box's move [dx dy] (px), nearest first in the order given."
  [boxes gap]
  (first (reduce (fn [[out placed] box]
                   (let [[dx dy :as mv] (apply min-key cost (map #(push box placed gap %) [:up :left :right]))]
                     [(conj out mv) (conj placed (-> box (update :x + dx) (update :y + dy)))]))
                 [[] []] boxes)))
