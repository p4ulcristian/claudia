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
  (the clip is thrown away) drag-px or more up and more up than sideways, :now (the running turn
  stops and the words go at once) drag-px or more down and more down than sideways, :add (the text
  joins the message) drag-px or more right, else :send (in place or dragged left)."
  [dx dy]
  (cond
    (and (<= dy (- drag-px)) (> (js/Math.abs dy) (js/Math.abs dx))) :cancel
    (and (>= dy drag-px) (> (js/Math.abs dy) (js/Math.abs dx))) :now
    (>= dx drag-px) :add
    :else :send))

(defn half-gesture
  "The half flower's way (README decision 73): its petals open to one side of the mic, side :left
  (the mic at the right wall or on its rail) or :right (at the left wall). From drag-px on, the
  quarter of that half the finger points into, top to bottom :cancel :add :send :now (each 45
  degrees, mirrored for :right); nearer, or on the other side of the mic, :send."
  ([dx dy] (half-gesture dx dy :left))
  ([dx dy side]
   (let [dx (if (= side :right) (- dx) dx)]
     (if (or (< (js/Math.hypot dx dy) drag-px) (pos? dx))
       :send
       (let [a (* (/ 180 js/Math.PI) (js/Math.atan2 dy dx))]
         (cond (<= -135 a -90) :cancel
               (< a -135) :add
               (>= a 135) :send
               :else :now))))))

(def move-px
  "How far the finger goes from where the hold started for the mic to follow it (past the petals,
  116 px out, and a finger's width more): the clip is dropped and the mic moves."
  130)

(def move-window-ms
  "How long after the press a drag can still move the mic: later the hold is talk, and a drag only
  picks a petal."
  2000)

(defn moving?
  "Whether a drag of dx, dy px from where the hold started, held-ms after the press, moves the mic."
  [dx dy held-ms]
  (and (< held-ms move-window-ms) (> (js/Math.hypot dx dy) move-px)))

(def edge-margin
  "How far a moved mic's centre stays from the left wall and the rail: the button whole, a finger's
  room beside it."
  44)

(def flower-room
  "How far a moved mic's centre stays from the top and the bottom, and how much room beside it the
  whole flower needs: its petals reach 116 px out."
  124)

(def home-px
  "How near its slot a mic let go goes back into the rail."
  40)

(defn clamp-spot
  "A moved mic's centre {:x :y}: edge-margin from the left wall and the rail (the right rail-w px),
  flower-room from the top and the bottom of a w x h screen."
  [{:keys [x y]} w h rail-w]
  {:x (-> x (max edge-margin) (min (- w rail-w edge-margin)))
   :y (-> y (max flower-room) (min (- h flower-room)))})

(defn home?
  "Whether a mic let go at {:x :y} goes back into the rail: let go on the rail itself (x past w -
  rail-w), or within home-px of its slot {:x :y}."
  [{:keys [x y]} slot w rail-w]
  (or (> x (- w rail-w))
      (and (some? slot) (< (js/Math.hypot (- x (:x slot)) (- y (:y slot))) home-px))))

(defn flower-side
  "Which flower a mic at x opens on a w-wide screen whose right rail-w px are the rail: :left (half,
  to the left) without the whole flower's room to its right, :right (half, to the right) without it
  to its left, else nil (whole)."
  [x w rail-w]
  (cond (< (- w rail-w x) flower-room) :left
        (< x flower-room) :right
        :else nil))

(defn spot->stored "A spot as fractions of the screen, kept per device." [{:keys [x y]} w h]
  {:fx (/ x w) :fy (/ y h)})

(defn stored->spot "A kept spot on a w x h screen, clamped (the screen may have turned)." [{:keys [fx fy]} w h rail-w]
  (when (and (number? fx) (number? fy))
    (clamp-spot {:x (* fx w) :y (* fy h)} w h rail-w)))

(defn clock-text
  "A recording's time in ms with its milliseconds, so the start shows at once: \"0.000\",
  \"12.345\" under a minute, \"1:02.345\" from then on."
  [ms]
  (let [ms (js/Math.floor (max 0 ms))
        s (quot ms 1000)
        milli (.padStart (str (mod ms 1000)) 3 "0")]
    (if (< s 60)
      (str s "." milli)
      (str (quot s 60) ":" (.padStart (str (mod s 60)) 2 "0") "." milli))))

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

(defn device-edge
  "Which edge of the device (held upright, in its natural portrait) the microphone iOS records
  from sits at: :top or :bottom; nil when it is not the built-in microphone or iOS did not say."
  [{:keys [port location orientation]}]
  (when (= port "MicrophoneBuiltIn")
    (case location
      "Upper" :top
      "Lower" :bottom
      (case orientation
        ("Top" "Front" "Back") :top
        "Bottom" :bottom
        nil))))

(defn speak-edge
  "The screen edge to speak at (:top :bottom :left :right) for mic (the app's report) with the
  screen turned angle degrees counterclockwise from portrait (screen.orientation.angle), or nil."
  [mic angle]
  (when-let [e (device-edge mic)]
    (let [turns {0 {:top :top :bottom :bottom} 90 {:top :left :bottom :right}
                 180 {:top :bottom :bottom :top} 270 {:top :right :bottom :left}}]
      (get-in turns [(mod (or angle 0) 360) e]))))

(defn turn-angle
  "How far the screen is turned from portrait, counterclockwise (0 90 180 270): the app's own word
  (native) first, then window.orientation (WebKit keeps it on iOS; -90 is 270), then
  screen.orientation.angle (0 in landscape in the iPad app's web view), else 0."
  [native window-orientation screen-angle]
  (let [a (or native window-orientation screen-angle 0)]
    (mod a 360)))

