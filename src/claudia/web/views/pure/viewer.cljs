(ns claudia.web.views.pure.viewer
  "The media viewer's decisions, as data (claudia.web.views.viewer, README decision 84): which item
  comes next, what a one-finger drag on an unzoomed photo does when it is let go, how the photo
  follows that finger, and the file a step showed.")

(def swipe-px
  "How far a finger moves sideways before letting go shows the next photo (less on a narrow window:
  a fifth of its width)."
  80)

(def close-px "How far a finger drags a photo down before letting go closes the viewer." 110)

(defn step
  "The index after moving dir (-1 back, 1 on) from i among n items, or nil past either end."
  [i n dir]
  (let [j (+ i dir)]
    (when (< -1 j n) j)))

(defn- abs* [v] (js/Math.abs v))

(defn swipe-outcome
  "What a one-finger drag by (dx, dy) px on an unzoomed photo does when let go: :next (dragged left),
  :prev (right), :close (down), or nil (it goes back in place). w: the stage's width, n: how many
  items the viewer holds (sideways means nothing with one)."
  [dx dy w n]
  (let [side (min swipe-px (* 0.2 w))]
    (cond (and (> n 1) (> (abs* dx) (abs* dy)) (> (abs* dx) side)) (if (neg? dx) :next :prev)
          (and (> dy close-px) (> dy (* 1.5 (abs* dx)))) :close
          :else nil)))

(defn drag-offset
  "[x y] px a photo dragged by (dx, dy) is shown moved: along the main direction only; sideways
  with the finger when there are n > 1 items (a third of it with one), down with the finger, up a
  third (it can only close downwards)."
  [dx dy n]
  (if (>= (abs* dx) (abs* dy))
    [(if (> n 1) dx (/ dx 3)) 0]
    [0 (if (pos? dy) dy (/ dy 3))]))

(defn path-in
  "The path a show_image or show_video step's detail (its input as JSON) names, or nil."
  [detail]
  (when-let [p (second (re-find #"\"path\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"" (str detail)))]
    (try (js/JSON.parse (str "\"" p "\"")) (catch :default _ p))))
