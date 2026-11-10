(ns claudia.web.circle
  "Circling over the window (a chalk line between two right clicks), as plain data: the line's points, the zone it encloses, the crop of a tab capture frame, and the note that tells
  Claude what was circled."
  (:require [clojure.string :as str]))

;; A right press that moves less than this (CSS px) is a click, not a circle.
(def ^:private min-travel 8)
;; Room around the line's box in the image (CSS px).
(def pad 16)
;; Texts under the chalk named in the note, at most.
(def ^:private max-texts 12)
;; A text longer than this is a whole column's, not what was circled.
(def ^:private max-text-length 300)

(defn travel
  "How far the line strays from its first point (CSS px)."
  [points]
  (let [[x0 y0] (first points)]
    (reduce (fn [m [x y]] (max m (js/Math.hypot (- x x0) (- y y0)))) 0 points)))

(defn drawn?
  "The press drew a line (not a plain right click)."
  [points]
  (>= (travel points) min-travel))

(defn bbox
  "The points' box {:x :y :w :h}."
  [points]
  (let [xs (map first points) ys (map second points)
        x (apply min xs) y (apply min ys)]
    {:x x :y y :w (- (apply max xs) x) :h (- (apply max ys) y)}))

(defn shift
  "Points moved by (dx, dy): stage coordinates to the window's and back."
  [points dx dy]
  (mapv (fn [[x y]] [(+ x dx) (+ y dy)]) points))

(defn clip
  "Box b grown by p on every side and cut to box v; nil when nothing is left."
  [{:keys [x y w h]} p v]
  (let [x0 (max (:x v) (- x p)) y0 (max (:y v) (- y p))
        x1 (min (+ (:x v) (:w v)) (+ x w p)) y1 (min (+ (:y v) (:h v)) (+ y h p))]
    (when (and (> x1 x0) (> y1 y0))
      {:x x0 :y y0 :w (- x1 x0) :h (- y1 y0)})))

(defn crop
  "A window box in a frame's pixels: sx, sy = frame pixels per CSS px across and down."
  [{:keys [x y w h]} sx sy]
  {:x (js/Math.round (* x sx)) :y (js/Math.round (* y sy))
   :w (max 1 (js/Math.round (* w sx))) :h (max 1 (js/Math.round (* h sy)))})

(defn path-d
  "SVG path data through the points."
  [points]
  (str/join " " (map-indexed (fn [i [x y]] (str (if (zero? i) "M" "L") (.toFixed x 1) " " (.toFixed y 1))) points)))

(defn grid
  "n × n points spread over box b, where to look for what lies under the chalk."
  [{:keys [x y w h]} n]
  (for [i (range n) j (range n)]
    [(+ x (* w (/ (+ i 0.5) n))) (+ y (* h (/ (+ j 0.5) n)))]))

(defn label-text
  "An element's text, trimmed and on one line; nil when empty or too long to be what was circled."
  [s]
  (let [t (str/trim (str/replace (str s) #"\s+" " "))]
    (when (and (seq t) (<= (count t) max-text-length)) t)))

(defn outermost
  "Texts without those another of them holds: a card's text holds its label's and its note's, a
  paragraph's its code words', so only the card and the paragraph stay."
  [texts]
  (let [ts (distinct texts)]
    (filterv (fn [t] (not-any? #(and (not= % t) (str/includes? % t)) ts)) ts)))

(defn- block-line [{:keys [id kind form] block-name :name}]
  (str (if (seq block-name) (str "\"" block-name "\"") "a block")
       " (" (or (not-empty form) kind) ", id " id ")"))

(def ^:private where {"chat" "in the chat" "app" "across the chat and the picture"})

(defn note
  "What a circle tells Claude, before its image (n: its place among the images sent): where it was
  drawn (the picture or the chat), the blocks it touches and the texts under it."
  [n {:keys [surface blocks texts]}]
  (str "[Circled " (get where surface "on the picture") ": image " n " below"
       (when (seq blocks) (str "; on " (str/join ", " (map block-line blocks))))
       (when (seq texts) (str "; under the chalk: " (str/join " · " (map #(str "\"" % "\"") (take max-texts texts)))))
       "]"))

(defn circles
  "The draft's circles: its attachments that came from the chalk, in order."
  [items]
  (filterv :circle items))

(defn- overlaps? [a b]
  (boolean (and a b (< (:x a) (+ (:x b) (:w b))) (< (:x b) (+ (:x a) (:w a)))
                (< (:y a) (+ (:y b) (:h b))) (< (:y b) (+ (:y a) (:h a))))))

(defn surface
  "Where zone z lies, given the chat's box and the picture's box (nil when not there): \"chat\",
  \"picture\" or \"app\" (both, or neither)."
  [z chat picture]
  (let [c? (overlaps? z chat) p? (overlaps? z picture)]
    (cond (and c? (not p?)) "chat"
          (and p? (not c?)) "picture"
          :else "app")))

(defn with-notes
  "The draft's attachments with every circle's note written, numbered by its image's place among
  all the images sent (a circle removed or a photo added before it renumbers the rest)."
  [items]
  (let [before (reductions + 0 (map (comp count :images) items))]
    (mapv (fn [item k]
            (if-let [info (:circle item)]
              (assoc item :note (note (inc k) info))
              item))
          items before)))

(defn from-frame
  "A pointer message a page frame forwarded ({:kind :x :y} in the frame's coordinates) in the
  window's, given the frame's box."
  [{:keys [kind x y]} {fx :x fy :y}]
  {:kind kind :x (+ x fx) :y (+ y fy)})
