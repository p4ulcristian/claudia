(ns claudia.web.scene3d.maker
  "The page's side of making a 3D scene's video off screen (the runner's headless browser drives
  it, claudia.slave.video): begin plays the story from step 1 on the stepped clock; frames hands out
  the next frames as JPEGs, 1/30 s apart, until the story has ended (a scene without a story runs
  video/still-seconds); said tells when the narrator began each line, for the sound."
  (:require [claudia.shared.narration :as narration]
            [claudia.web.scene3d.clock :as clock]
            [claudia.web.scene3d.frames :as frames]
            [claudia.web.scene3d.video :as video]))

(def fps 30)
(def ^:private frame-ms (/ 1000 fps))
(def ^:private max-seconds (* 20 60))

(defn- elapsed [t0] (/ (- (clock/now) t0) 1000))

(defn- done?
  "The video has all its frames: the story has ended (or, without one, still-seconds have run),
  or it has reached max-seconds."
  [v t0]
  (let [secs (elapsed t0)]
    (or (>= secs max-seconds)
        (if (seq ((:steps v))) (not ((:playing? v))) (>= secs video/still-seconds)))))

(defn- frame!
  "The next frame as a JPEG (the first is the one begin drew)."
  [v f k]
  (when (pos? k) ((:step! v) frame-ms))
  (frames/paint! f)
  (frames/jpeg f))

(defn- batch
  "Promise of {:frames [base64…] :done} with up to n more frames; k: frames handed out so far."
  [v f t0 k n]
  (letfn [(more [out i]
            (if (or (>= i n) (and (pos? (+ k i)) (done? v t0)))
              (js/Promise.resolve {:frames out :done (done? v t0) :handed (+ k i)})
              (.then (frame! v f (+ k i)) #(more (conj out %) (inc i)))))]
    (more [] 0)))

(defn- said-seconds [said t0]
  (mapv (fn [{:keys [text instruction at]}] {:text text :instruction instruction :at (/ (- at t0) 1000)}) said))

(defn make
  "The maker for a scene started with engine/start!'s video option: api is its api, host the
  view's element, seconds the clips' lengths ({[instruction text] seconds}), data the scene's data.
  -> a JS object: begin() -> {seconds, fps} (the expected length), frames(n) -> Promise of {frames,
  done}, said() -> [{text, instruction, at}] (seconds into the video)."
  [api ^js host seconds data]
  (let [v (:video api)
        f (frames/make host (:gl v))
        t0 (atom 0)
        handed (atom 0)
        instruction (narration/voice-of data)]
    #js {:begin (fn []
                  ((:begin! v))
                  (reset! t0 (clock/now))
                  (clj->js {:seconds (video/story-seconds ((:steps v)) #(get seconds [instruction %])) :fps fps}))
         :frames (fn [n]
                   (.then (batch v f @t0 @handed n)
                          (fn [b]
                            (reset! handed (:handed b))
                            (clj->js (dissoc b :handed)))))
         :said (fn [] (clj->js (said-seconds ((:said v)) @t0)))}))
