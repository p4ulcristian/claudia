(ns claudia.web.scene3d.recording
  "The numbers and names of a scene3d recording, without the page: which video format, the video's
  size, where each frame's picture sits in it, where a word's baseline is.")

(def mime-types
  "The formats a recording tries, best first: MP4 with H.264 plays everywhere (phones, QuickTime);
  WebM where the browser has no H.264 encoder. A bare video/mp4 is not asked for: without H.264 it
  holds VP9, which many players refuse."
  ["video/mp4;codecs=avc1.640028,mp4a.40.2"
   "video/mp4;codecs=avc1.640028,opus"
   "video/webm;codecs=vp9,opus"
   "video/webm;codecs=vp8,opus"
   "video/webm"])

(defn pick-type
  "The first of mime-types the browser records (supported? answers for one), or nil."
  [supported?]
  (some #(when (supported? %) %) mime-types))

(defn extension "A recording's file extension by its format." [mime]
  (if (re-find #"^video/mp4" (str mime)) "mp4" "webm"))

(defn- even [n] (* 2 (js/Math.floor (/ n 2))))

(defn video-size
  "The video's [width height] in pixels: the stage (w by h CSS pixels) at the screen's pixel ratio,
  shrunk so the long side is at most max-side; both even (encoders need it), at least 2."
  [w h ratio max-side]
  (let [s (min ratio (/ max-side (max w h 1)))]
    [(max 2 (even (* w s))) (max 2 (even (* h s)))]))

(defn fit
  "Where a stage of sw by sh CSS pixels sits in a video of vw by vh: its scale and offset, whole and
  centred (a stage resized while recording keeps its shape, with bands)."
  [sw sh vw vh]
  (let [s (min (/ vw (max sw 1)) (/ vh (max sh 1)))]
    {:scale s :x (/ (- vw (* sw s)) 2) :y (/ (- vh (* sh s)) 2)}))

(defn baseline
  "Where a line of text whose box runs from top, height tall, has its baseline, for a font with
  this ascent and descent (the box centres the font's own height)."
  [top height ascent descent]
  (+ top (/ (- height ascent descent) 2) ascent))

(defn word-spans
  "Where each word of a text starts and ends: [[start end] …], spaces left out."
  [text]
  (let [re (js/RegExp. "\\S+" "g")]
    (loop [out []]
      (if-let [m (.exec re text)]
        (recur (conj out [(.-index m) (+ (.-index m) (count (aget m 0)))]))
        out))))

