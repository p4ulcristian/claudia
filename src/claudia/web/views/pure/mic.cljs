(ns claudia.web.views.pure.mic
  "The composer's mic, as data: what heard text does to the draft, how long a hold has to be, what
  a drag while holding chooses, and the bars drawn round the button from the voice's spectrum."
  (:require [clojure.string :as str]))

(def bar-count "Bars round the button." 48)

(def min-hold-ms
  "A hold shorter than this is a tap: nothing is sent to be heard (the button says to hold it)."
  350)

(def drag-px
  "How far right of where the hold started the finger goes for the clip to join the message
  instead of being sent."
  40)

(defn gesture
  "What letting go does after a drag of dx px from where the hold started: :add (the text joins
  the message, nothing is sent) right of drag-px, else :send (in place or dragged left)."
  [dx]
  (if (>= dx drag-px) :add :send))

(defn with-heard
  "The draft with heard text added at its end, one space between (a new line kept as it is)."
  [draft heard]
  (let [d (str draft) h (str/trim (str heard))]
    (cond
      (str/blank? h) d
      (or (str/blank? d) (re-find #"\s$" d)) (str d h)
      :else (str d " " h))))

(defn- band
  "The mean of bins [from, to) of a spectrum (0-255 each), as 0..1."
  [spectrum from to]
  (let [n (max 1 (- to from))]
    (/ (reduce + (map #(nth spectrum % 0) (range from to))) n 255)))

(defn bar-levels
  "A spectrum (bytes 0-255, low to high) as bar-count levels 0..1: the speech bands (the lower
  half) spread over half the circle and mirrored, so the ring is even left and right."
  [spectrum]
  (let [bins (quot (count spectrum) 2)
        half (quot bar-count 2)
        side (vec (for [i (range half)]
                    (band spectrum (quot (* i bins) half) (quot (* (inc i) bins) half))))]
    (into side (rseq side))))

(defn eased
  "Bars moving towards their new levels: quick up, slower down."
  [old fresh]
  (mapv (fn [o n] (if (> n o) (+ o (* 0.6 (- n o))) (+ o (* 0.2 (- n o)))))
        (or (seq old) (repeat (count fresh) 0)) fresh))
