(ns claudia.web.views.pure.mic
  "The composer's mic, as data: what heard text does to the draft, how long a hold has to be, what
  a drag while holding chooses, and the bars drawn round the button from the voice's spectrum."
  (:require [clojure.string :as str]
            [claudia.web.views.pure.update-pill :as clock]))

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

(defn circle-way
  "What a drag chooses on a voice message's circle (README decision 107): the mic's way, but left
  skips all of that chat's waiting messages (:skip) instead of adding to a draft."
  [dir]
  (if (= dir :add) :skip dir))

(def ring-in
  "The move ring's inner edge, px from the mic's centre: just outside the petals (petal-reach). A
  finger that reaches it while holding a mic that moves keeps recording and the mic follows it;
  let go, the clip is sent as when let go in place."
  124)

(def ring-out "The move ring's outer edge, px from the mic's centre." 148)

(defn arc-letters
  "Where each letter of a word goes when it is written along a circle of radius r, centred on angle a
  (radians, 0 right, y down), widths its letters' widths in px: [{:angle :turn}], the angle of the
  letter's middle and its turn. Over the middle it runs clockwise, under it counterclockwise, so the
  word always reads upright, left to right."
  [widths r a]
  (let [dir (if (neg? (js/Math.sin a)) 1 -1)
        start (- a (* dir (/ (reduce + widths) 2 r)))]
    (mapv (fn [before w]
            (let [t (+ start (* dir (/ (+ before (/ w 2)) r)))]
              {:angle t :turn (+ t (* dir (/ js/Math.PI 2)))}))
          (reductions + 0 widths) widths)))

(defn in-ring?
  "Whether a finger dx, dy px from the mic's centre is in the move ring or past it."
  [dx dy]
  (>= (js/Math.hypot dx dy) ring-in))

(def settle-ms
  "How long a finger moving the mic rests before the mic is put down under it and the hold chooses
  again from there (the petals round it, the ring to move on)."
  200)

(def rest-px "How far a resting finger may still drift (a finger trembles)." 8)

(defn still?
  "Whether the finger at x y is still where it rested (at {:x :y}), within rest-px."
  [at x y]
  (< (js/Math.hypot (- x (:x at)) (- y (:y at))) rest-px))

(def petal-reach
  "How far the flower's petals reach from the mic's centre, px (claudia.web.views.mic-flower)."
  116)

(defn clamp-spot
  "A floating mic's centre {:x :y} on a w x h screen, so its whole flower (petal-reach round it)
  stays on it: every petal can be reached on every device (README decision 110). A screen too
  small for the flower one way keeps the mic in its middle that way."
  [{:keys [x y]} w h]
  (let [mx (min petal-reach (/ w 2)) my (min petal-reach (/ h 2))]
    {:x (-> x (max mx) (min (- w mx)))
     :y (-> y (max my) (min (- h my)))}))

(defn spot->stored "A spot as fractions of the screen, kept per device." [{:keys [x y]} w h]
  {:fx (/ x w) :fy (/ y h)})

(defn stored->spot
  "A kept spot on a w x h screen, clamped so the whole flower stays on it (the screen may have
  turned)."
  [{:keys [fx fy]} w h]
  (when (and (number? fx) (number? fy))
    (clamp-spot {:x (* fx w) :y (* fy h)} w h)))

(defn default-spot
  "Where a floating mic sits until it is moved, on a w x h screen: beside the edge column (edge-w
  px wide) at the bottom right; on a phone (edge-w 0) in the bottom middle, its button (radius r)
  just over the composer (whose top is composer-top). Clamped so the whole flower fits."
  [{:keys [w h edge-w composer-top r]}]
  (clamp-spot (if (pos? edge-w)
                {:x (- w edge-w 110) :y (- h 110)}
                {:x (/ w 2) :y (- (or composer-top h) 8 r)})
              w h))

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

(def context-chars
  "How much of a chat's last words goes with a clip as context for the listen service (it keeps the
  last 200 words of it)."
  2000)

(defn listen-context
  "The chat's last words as context for hearing a clip: the person's and Claude's messages, oldest
  first, one per line, cut to their last context-chars at a word's start (\"\" for none)."
  [messages]
  (let [text (->> messages
                  (filter #(#{"user" "assistant"} (:role %)))
                  (keep #(not-empty (str/trim (str (:text %)))))
                  (take-last 6)
                  (str/join "\n"))
        cut (- (count text) context-chars)]
    (if (pos? cut)
      (str/triml (str/replace-first (subs text cut) #"^\S*" ""))
      text)))

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


;; ---- a clip's pill (views/mic_clips.cljs) ----

(def ^:private ways "Where a clip's words go: its mark and its word." {:add ["+" "add"] :now ["⏵" "now"]})

(defn way-mark "The mark for where a clip's words go (dir :send, :add or :now)." [dir] (first (get ways dir ["↑" "send"])))

(defn- status-text
  "A clip's state in words."
  [{:keys [state t0]} now]
  (case state
    :failed "didn't come through"
    :heard "heard, waits its turn"
    (str "hearing " (clock/clock-text (- now t0)))))

(defn clip-label
  "Clip c ({:n :secs :dir :state :t0}) in words, for its tip and screen readers, at now."
  [{:keys [n secs dir] :as c} now]
  (str "Clip " (inc n) ", " secs " s, " (second (get ways dir ["↑" "send"])) ": " (status-text c now)))

(defn tap-plan
  "What a tap does (README decision 120), from the press: circle (the chat whose circle was tapped,
  nil for the mic), reading and reading-msg (the chat and voice message being read at the press,
  before it stopped). While a reply is read, a tap on the mic or on that chat's circle skips it:
  :heard [chat [msg]] and :play that chat (its next waiting one); a tap on another chat's circle only
  :play-s it; a tap on the mic with nothing read gives the hint (:phase :tap, else :idle)."
  [{:keys [circle reading reading-msg]}]
  (let [skipped (when (and reading (or (nil? circle) (= circle reading))) reading)
        k (or circle skipped)]
    (cond-> {:phase (if k :idle :tap)}
      (and skipped reading-msg) (assoc :heard [skipped [reading-msg]])
      k (assoc :play k))))
