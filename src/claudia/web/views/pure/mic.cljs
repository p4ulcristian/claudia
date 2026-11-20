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
  "What letting go does after a drag of dx, dy px (y down) from where the hold started: :send
  drag-px or more up and more up than sideways, :now (the running turn stops and the words go at
  once) drag-px or more down and more down than sideways, :cancel (the clip is thrown away) drag-px
  or more right, :add (the text joins the message) drag-px or more left, else (in place) :send."
  [dx dy]
  (let [vertical? (> (js/Math.abs dy) (js/Math.abs dx))]
    (cond
      (and vertical? (<= dy (- drag-px))) :send
      (and vertical? (>= dy drag-px)) :now
      (>= dx drag-px) :cancel
      (<= dx (- drag-px)) :add
      :else :send)))

(defn half-gesture
  "The half flower's way (README decision 73: the mic in its rail on the right wall, its petals only
  to the left): from drag-px on, the left half's quarter the finger points into, top to bottom
  :cancel :add :send :now (each 45 degrees); nearer, or to the right of the mic, :send."
  [dx dy]
  (if (or (< (js/Math.hypot dx dy) drag-px) (pos? dx))
    :send
    (let [a (* (/ 180 js/Math.PI) (js/Math.atan2 dy dx))]
      (cond (<= -135 a -90) :cancel
            (< a -135) :add
            (>= a 135) :send
            :else :now))))

(def ring-in
  "The move ring's inner edge, px from the mic's centre: just outside the petals (116 px out). A
  finger that reaches it while holding a mic that moves keeps recording and the mic follows it;
  let go, the clip is sent as when let go in place."
  124)

(def ring-out "The move ring's outer edge, px from the mic's centre." 148)

(defn in-ring?
  "Whether a finger dx, dy px from the mic's centre is in the move ring or past it."
  [dx dy]
  (>= (js/Math.hypot dx dy) ring-in))

(def settle-ms
  "How long a finger moving the mic rests before the mic is put down under it and the hold chooses
  again from there (the petals round it, the ring to move on)."
  350)

(def rest-px "How far a resting finger may still drift (a finger trembles)." 8)

(defn still?
  "Whether the finger at x y is still where it rested (rest {:x :y}), within rest-px."
  [rest x y]
  (< (js/Math.hypot (- x (:x rest)) (- y (:y rest))) rest-px))

(defn clamp-spot
  "A moved mic's centre {:x :y} on a w x h screen, so its button (radius r) stays wholly on it."
  [{:keys [x y]} w h r]
  {:x (-> x (max r) (min (- w r)))
   :y (-> y (max r) (min (- h r)))})

(defn overlap-area
  "The area two circles of radii r1, r2 whose centres are d apart have in common."
  [d r1 r2]
  (cond
    (>= d (+ r1 r2)) 0
    (<= d (js/Math.abs (- r1 r2))) (* js/Math.PI (js/Math.pow (min r1 r2) 2))
    :else (let [part (fn [ra rb] (* ra ra (js/Math.acos (/ (+ (* d d) (* ra ra) (- (* rb rb))) (* 2 d ra)))))]
            (- (+ (part r1 r2) (part r2 r1))
               (* 0.5 (js/Math.sqrt (* (+ (- d) r1 r2) (+ d r1 (- r2)) (+ d (- r1) r2) (+ d r1 r2))))))))

(defn home?
  "Whether a mic let go as button {:x :y :r} goes back into the rail: it covers half or more of its
  slot {:x :y :r}'s area."
  [button slot]
  (and (some? slot)
       (>= (overlap-area (js/Math.hypot (- (:x button) (:x slot)) (- (:y button) (:y slot))) (:r button) (:r slot))
           (* 0.5 js/Math.PI (:r slot) (:r slot)))))

(defn flower-side
  "Which flower a mic at x opens on a w-wide screen whose right rail-w px are the rail: in the rail
  :left (half, to the left), out of it nil (the whole flower, four ways)."
  [x w rail-w]
  (when (> x (- w rail-w)) :left))

(defn spot->stored "A spot as fractions of the screen, kept per device." [{:keys [x y]} w h]
  {:fx (/ x w) :fy (/ y h)})

(defn stored->spot
  "A kept spot on a w x h screen, clamped so a button of radius r stays on it (the screen may have
  turned)."
  [{:keys [fx fy]} w h r]
  (when (and (number? fx) (number? fy))
    (clamp-spot {:x (* fx w) :y (* fy h)} w h r)))

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

