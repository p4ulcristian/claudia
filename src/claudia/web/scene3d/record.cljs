(ns claudia.web.scene3d.record
  "Recording a 3D scene as a video file. Every frame the engine draws, frame! paints one picture on
  a canvas of its own: the page's background, the 3D drawing, then the labels, the legend and the
  subtitles as the page shows them. That canvas's stream, with the narrator's voice when it is on,
  goes to the browser's MediaRecorder; stop! ends it and the file downloads."
  (:require [claudia.web.download :as download]
            [claudia.web.scene3d.paint :as paint]
            [claudia.web.scene3d.recording :as rec]))

(def ^:private max-side 1920)
(def ^:private fps 30)
(def ^:private overlays [".s3-labels" ".s3-legend" ".s3-sub"])

(defn supported?
  "Whether this browser can record a canvas into a video."
  []
  (and (exists? js/MediaRecorder) (exists? js/HTMLCanvasElement)
       (some? (.. js/HTMLCanvasElement -prototype -captureStream))
       (some? (rec/pick-type #(.isTypeSupported js/MediaRecorder %)))))

(defn- opaque-background
  "The colour behind the stage: the first ancestor's background that is not see-through."
  [^js el]
  (loop [^js e el]
    (if-not e
      "#000"
      (let [c (.-backgroundColor (js/getComputedStyle e))]
        (if (paint/see-through? c) (recur (.-parentElement e)) c)))))

(defn- canvas-of [[w h]]
  (let [c (js/document.createElement "canvas")]
    (set! (.-width c) w)
    (set! (.-height c) h)
    c))

(defn- stream-of [^js canvas ^js audio]
  (let [s (.captureStream canvas fps)]
    (when audio (doseq [t (array-seq (.getAudioTracks audio))] (.addTrack s t)))
    s))

(defn- media-recorder [^js stream mime chunks]
  (let [r (js/MediaRecorder. stream #js {:mimeType mime :videoBitsPerSecond 8000000})]
    (set! (.-ondataavailable r) (fn [^js e] (when (pos? (.. e -data -size)) (swap! chunks conj (.-data e)))))
    r))

(defn start!
  "Starts recording the scene in el (its .s3-stage, the renderer's canvas); audio: a MediaStream
  with the voice, or nil. title: the file's name without extension. -> the recording."
  [^js el ^js gl audio title]
  (let [stage (.querySelector el ".s3-stage")
        size (rec/video-size (.-clientWidth stage) (.-clientHeight stage) (min js/devicePixelRatio 2) max-side)
        canvas (canvas-of size)
        mime (rec/pick-type #(.isTypeSupported js/MediaRecorder %))
        chunks (atom [])
        r (media-recorder (stream-of canvas audio) mime chunks)]
    (.start r 1000)
    {:el el :stage stage :gl gl :canvas canvas :ctx (.getContext canvas "2d") :size size
     :background (opaque-background stage) :mime mime :chunks chunks :recorder r :title title
     :started (js/performance.now)}))

(defn seconds "How long it has recorded." [{:keys [started]}]
  (/ (- (js/performance.now) started) 1000))

(defn frame!
  "Paints the frame the engine just drew (call it right after rendering, while the drawing is
  still there)."
  [{:keys [^js el ^js stage ^js gl ^js ctx size background]}]
  (let [[vw vh] size
        ^js sr (.getBoundingClientRect stage)
        o {:left (.-left sr) :top (.-top sr)}
        {:keys [scale x y]} (rec/fit (.-width sr) (.-height sr) vw vh)]
    (.setTransform ctx 1 0 0 1 0 0)
    (set! (.-globalAlpha ctx) 1)
    (set! (.-fillStyle ctx) background)
    (.fillRect ctx 0 0 vw vh)
    (.setTransform ctx scale 0 0 scale x y)
    (.drawImage ctx gl 0 0 (.-width sr) (.-height sr))
    (doseq [sel overlays]
      (when-let [o-el (.querySelector el sel)] (paint/el! ctx o-el o 1)))))

(defn stop!
  "Ends the recording; when the last piece is in, the file downloads."
  [{:keys [^js recorder chunks mime title]}]
  (when (not= "inactive" (.-state recorder))
    (set! (.-onstop recorder)
          (fn [] (download/save-blob! (js/Blob. (clj->js @chunks) #js {:type mime})
                                      (download/file-name title "scene" (rec/extension mime)))))
    (.stop recorder)
    (doseq [t (array-seq (.getTracks (.-stream recorder)))] (.stop t))))
