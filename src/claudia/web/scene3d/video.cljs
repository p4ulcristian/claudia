(ns claudia.web.scene3d.video
  "The numbers of a 3D scene's video, without the page: its size, where each frame's picture sits
  in it, where a painted word's baseline is, how long the story runs; and the runner's list of a
  scene's videos as the bar shows it.

  A video as the runner lists it: {:id :state (voice, queued, render, encode, done, failed)
  :progress (0 to 1 while rendering) :seconds :error :at (ms, when asked for)}."
  (:require [claudia.web.scene3d.story :as story]))

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


(def still-seconds
  "How long the video of a scene without a story runs."
  15)

(defn step-seconds
  "How long step lasts in the video: its clip's length (clip-seconds, nil without one) or its
  estimate, at least its hold, plus the gap before the next step."
  [step clip-seconds]
  (+ (if clip-seconds (max clip-seconds (:hold step)) (story/estimate-seconds step))
     story/gap-seconds))

(defn story-seconds
  "How long the video runs: every step (seconds-of: a line's clip length or nil), or still-seconds
  without a story."
  [steps seconds-of]
  (if (empty? steps)
    still-seconds
    (reduce + (map #(step-seconds % (seconds-of (:say %))) steps))))

(def ^:private making-states #{"voice" "queued" "render" "encode"})

(defn latest
  "What the bar shows of a scene's videos: {:making (the newest one being made) :done (the newest
  finished) :failed (the newest failure, when nothing is made and nothing finished since)}."
  [videos]
  (let [newest (reverse (sort-by :at videos))
        making (first (filter #(making-states (:state %)) newest))
        done (first (filter #(= "done" (:state %)) newest))
        failed (first (filter #(= "failed" (:state %)) newest))]
    {:making making
     :done done
     :failed (when (and failed (not making) (or (not done) (> (:at failed) (:at done)))) failed)}))

(defn making-text
  "How far a video being made is, in a few words."
  [{:keys [state progress]}]
  (case state
    "voice" "Voicing the story…"
    "queued" "Waiting for another video…"
    "render" (str "Making video " (js/Math.floor (* 100 (or progress 0))) "%")
    "encode" "Finishing the video…"
    ""))

(defn just-done
  "The videos finished between two answers: done in after, being made in before (none on the
  first answer, before nil)."
  [before after]
  (let [was (into {} (map (juxt :id :state)) before)]
    (filter #(and (= "done" (:state %)) (making-states (get was (:id %)))) after)))
