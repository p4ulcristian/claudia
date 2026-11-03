(ns claudia.web.views.pure.shell
  "The layout's numbers as plain data: the chat column's width while the divider is dragged, the
  split's columns, the scrubber's marks.")

(defn dragged-width
  "The chat column's width in px with the divider at client-x: at least 300, leaving 280 and the
  6 px divider to the picture."
  [main-left main-width client-x]
  (js/Math.round (min (- main-width 6 280) (max 300 (- client-x main-left)))))

(defn split-columns
  "The split's grid columns: chat | divider | picture, or the picture folded to a 16 px edge."
  [folded? chat-w]
  (if folded?
    "minmax(300px, 1fr) 6px 16px"
    (str "max(300px, min(" chat-w "px, calc(100% - 286px))) 6px minmax(280px, 1fr)")))

(defn mark-at
  "Where picture i of n sits along the scrubber, in percent."
  [n i]
  (if (= n 1) 100 (* 100 (/ i (dec n)))))

(defn nearest-mark
  "The picture nearest to x on a track from left, width px wide, of n pictures."
  [n left width x]
  (let [f (min 1 (max 0 (/ (- x left) (max 1 width))))]
    (js/Math.round (* f (dec n)))))

(defn density
  "How close the marks are: room (\"\"), dense, packed."
  [n width]
  (let [gap (if (> n 1) (/ width (dec n)) width)]
    (cond (>= gap 14) "" (>= gap 4) "dense" :else "packed")))

(defn tip-left
  "The hover label's left: over its mark (at x, w wide) but 8 px inside the box from box-left to
  box-right."
  [x w box-left box-right]
  (max (+ box-left 8) (min x (- box-right w 8))))
