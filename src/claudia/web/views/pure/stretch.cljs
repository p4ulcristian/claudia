(ns claudia.web.views.pure.stretch
  "The chat's rubber-band end (README decision 118), as numbers: whether the device bounces by
  itself, where the column stands (its top, its bottom), how far a push past an end has gone and
  how far the messages move for it. The pull is signed: plus at the top (the messages move down),
  minus at the bottom (they move up).")

(def limit-px
  "The messages never move further than this, however long the push."
  110)

(def give
  "How stiff the band is: the first pixels follow at this share, then less and less (iOS uses 0.55)."
  0.55)

(def line-px
  "A wheel step given in lines (deltaMode 1) is this many pixels; pages (2) are a column's height."
  16)

(defn native-bounce?
  "Whether the browser bounces the column by itself: every Apple device (iPhone, iPad, also an iPad
  asking for the desktop site, and the Mac with any browser), told by the platform, the user agent
  and touch points."
  [platform ua touch-points]
  (boolean (or (re-find #"Mac|iPhone|iPad|iPod" (str platform))
               (re-find #"iPhone|iPad|iPod" (str ua))
               (and (= "MacIntel" platform) (pos? touch-points)))))

(defn ends
  "Which ends the column stands at, from its scroll numbers: #{:top :bottom}, one of them, or none.
  A column shorter than its messages' box stands at both."
  [{:keys [top height client]}]
  (cond-> #{}
    (<= top 0) (conj :top)
    (>= (+ top client) (dec height)) (conj :bottom)))

(defn- toward-zero
  "pull moved by dy towards 0, never past it."
  [pull dy]
  (if (pos? pull) (max 0 (- pull dy)) (min 0 (+ pull dy))))

(defn next-pull
  "The pull after a scroll of dy px (plus: down the messages, as a wheel turned towards you or a
  finger moving up) with the column at ends-now: a push past an end adds to it, a scroll back takes
  from it (never past 0), and a column at no end has none."
  [pull dy ends-now]
  (cond
    (and (neg? dy) (:top ends-now) (>= pull 0)) (- pull dy)
    (and (pos? dy) (:bottom ends-now) (<= pull 0)) (- pull dy)
    (not (zero? pull)) (toward-zero pull (js/Math.abs dy))
    :else 0))

(defn offset
  "How far the messages move (px, signed as the pull) for a pull: iOS's band, (1 - 1/(1 + give·p/L))·L,
  which follows almost one to one at first and never reaches limit-px."
  [pull]
  (let [p (js/Math.abs pull)
        o (* limit-px (- 1 (/ 1 (inc (/ (* give p) limit-px)))))]
    (if (neg? pull) (- o) o)))

(defn wheel-px
  "A wheel event's vertical step in px from its deltaY and deltaMode (0 px, 1 lines, 2 pages of
  client px)."
  [delta-y mode client]
  (case mode 1 (* delta-y line-px) 2 (* delta-y client) delta-y))

(defn can-scroll?
  "Whether a box with these scroll numbers still scrolls itself for a step dy (so a push inside it is
  its own, not the column's end): it is taller than its window and not at the end dy goes to."
  [{:keys [height client] :as m} dy]
  (and (> height (inc client))
       (not (contains? (ends m) (if (neg? dy) :top :bottom)))))
