(ns claudia.web.views.pure.shell
  "The layout's numbers as plain data: what the chat column shows, the chat column's width while
  the divider is dragged, the split's columns, the scrubber's marks and numbers.")

(defn column-pane
  "What takes the chat column wider than a phone (README decision 24): :archived (the archived
  chats) or nil (the chat). A phone shows them in its list instead, so nil there. Settings is a
  popup over everything (README decision 58), never the column."
  [phone? {:keys [archived?]}]
  (when-not phone? (when archived? :archived)))

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

(defn label-width
  "The px one step number of n pictures takes on the scrubber, a little room beside it included."
  [n]
  (+ 12 (* 7 (count (str n)))))

(def ^:private label-steps
  "Every how many pictures a number shows when not every one fits."
  [1 5 10 20 50 100 200 500 1000])

(defn- label-step
  "The fewest pictures between two numbers so that numbers gap px apart need px each do not touch;
  nil when even the widest step is too close."
  [gap need]
  (some #(when (>= (* gap %) need) %) label-steps))

(defn tick-kinds
  "What each of n marks on a track width px wide shows (README decision 97), index the picture
  shown: :number or :dot. Every number when they fit; else every 5th or 10th (20th …) and always
  the first, the last and the shown one, a number too close to one of those a dot."
  [n width index]
  (let [gap (if (> n 1) (/ width (dec n)) width)
        need (label-width n)
        near? (fn [i j] (< (* (js/Math.abs (- i j)) gap) need))
        ends (remove #(and (not= % index) (near? % index)) [0 (dec n)])
        fixed (set (conj ends index))
        step (label-step gap need)]
    (mapv (fn [i]
            (if (or (fixed i)
                    (and step (zero? (mod (inc i) step)) (not-any? #(near? i %) fixed)))
              :number
              :dot))
          (range n))))

(defn tip-left
  "The hover label's left: over its mark (at x, w wide) but 8 px inside the box from box-left to
  box-right."
  [x w box-left box-right]
  (max (+ box-left 8) (min x (- box-right w 8))))
