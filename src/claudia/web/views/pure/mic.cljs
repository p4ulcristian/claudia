(ns claudia.web.views.pure.mic
  "The composer's mic, as data: what heard text does to the draft, how long a hold has to be, what
  a drag while holding chooses, and the bars drawn round the button from the voice's spectrum."
  (:require [clojure.string :as str]))

(def bar-count "Bars round the button." 48)

(def min-hold-ms
  "A hold shorter than this is a tap: nothing is sent to be heard (the button says to hold it)."
  350)

(def drag-px
  "How far the finger goes from where the hold started for a drag to choose a petal."
  40)

(defn gesture
  "What letting go does after a drag of dx, dy px (y down) from where the hold started: :cancel
  (the clip is thrown away) drag-px or more up and more up than sideways, :add (the text joins the
  message) drag-px or more right, else :send (in place or dragged left or down)."
  [dx dy]
  (cond
    (and (<= dy (- drag-px)) (> (js/Math.abs dy) (js/Math.abs dx))) :cancel
    (>= dx drag-px) :add
    :else :send))

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

(def ^:private speech-hz "The voice's band the bars show: [low high] Hz." [90 4000])
(def ^:private floor-level "A band's level (0..1, -100..-30 dB) that is room noise: no bar." 0.3)
(def ^:private full-level "A band's level that fills its bar." 0.75)

(defn- edges
  "n+1 bin edges for n bands spread evenly on a log scale over speech-hz, for a 256-bin spectrum
  of a microphone at rate Hz (each bin rate/512 wide); every band at least one bin."
  [n rate]
  (let [[lo hi] speech-hz
        bin-hz (/ rate 512)
        at #(* lo (js/Math.pow (/ hi lo) (/ % n)))]
    (vec (reductions (fn [prev i] (max (inc prev) (js/Math.floor (/ (at i) bin-hz))))
                     (js/Math.floor (/ lo bin-hz))
                     (range 1 (inc n))))))

(defn- lifted
  "A band's level as a bar's: room noise none, a speaking voice most of it."
  [v]
  (-> (/ (- v floor-level) (- full-level floor-level)) (max 0) (min 1)))

(defn bar-levels
  "A spectrum (bytes 0-255, low to high, 256 bins) of a microphone at rate Hz as bar-count levels
  0..1: the voice's band (speech-hz, log spaced, so every bar has voice in it) over half the circle
  and mirrored, so the ring is even left and right."
  [spectrum rate]
  (let [half (quot bar-count 2)
        e (edges half (or rate 48000))
        side (vec (for [i (range half)] (lifted (band spectrum (e i) (e (inc i))))))]
    (into side (rseq side))))

(defn eased
  "Bars moving towards their new levels: quick up, slower down."
  [old fresh]
  (mapv (fn [o n] (if (> n o) (+ o (* 0.6 (- n o))) (+ o (* 0.2 (- n o)))))
        (or (seq old) (repeat (count fresh) 0)) fresh))

(defn in-order
  "The clips heard so far, given out in the order they were spoken: line is {:next n :held {n
  result}} (n counts the clips from 0 as they are let go), and result is clip n's outcome (nil when
  it failed or was empty: it holds nothing back). -> [line' results-to-give-out-now]."
  [line n result]
  (loop [held (assoc (:held line) n [result]) due (:next line) out []]
    (if-let [[r] (get held due)]
      (recur (dissoc held due) (inc due) (cond-> out r (conj r)))
      [(assoc line :next due :held held) out])))
