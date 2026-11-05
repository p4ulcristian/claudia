(ns claudia.web.scene3d.clock
  "The 3D scene's time, in milliseconds on the performance.now scale. On the page it is the real
  clock. While a video is made off screen it is a clock the maker steps a frame at a time, so the
  story, the script, fades, the camera's glides and people's poses all move by exactly one frame
  between two pictures however long drawing a picture takes.")

(defonce ^:private stepped (atom nil)) ; nil: the real clock; a number: the stepped clock's ms

(defn now "The time now (ms)." [] (or @stepped (js/performance.now)))

(defn stepped? "Whether a maker steps the clock." [] (some? @stepped))

(defn start-stepping!
  "From now on the clock only moves when stepped; it starts at the real time now."
  []
  (reset! stepped (js/performance.now)))

(defn step! "Moves the stepped clock on by ms." [ms] (swap! stepped + ms))
