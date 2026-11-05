(ns claudia.web.scene3d.frames
  "A video's pictures, made off screen: right after the engine draws a frame, paint! puts it on a
  canvas of its own (the page's background, the 3D drawing, then the labels, the legend and the
  subtitles as the page shows them; not the bar), and jpeg hands it over."
  (:require [claudia.web.scene3d.paint :as paint]
            [claudia.web.scene3d.video :as video]))

(def ^:private max-side 1920)
(def ^:private overlays [".s3-labels" ".s3-legend" ".s3-sub"])

(defn- opaque-background
  "The colour behind the stage: the first ancestor's background that is not see-through."
  [^js el]
  (loop [^js e el]
    (if-not e
      "#000"
      (let [c (.-backgroundColor (js/getComputedStyle e))]
        (if (paint/see-through? c) (recur (.-parentElement e)) c)))))

(defn make
  "The canvas a scene's video frames are painted on: the scene in el (its .s3-stage), drawn by
  WebGL on the canvas gl, at the screen's pixel ratio (the long side at most 1920)."
  [^js el ^js gl]
  (let [stage (.querySelector el ".s3-stage")
        [w h :as size] (video/video-size (.-clientWidth stage) (.-clientHeight stage) (min js/devicePixelRatio 2) max-side)
        canvas (js/document.createElement "canvas")]
    (set! (.-width canvas) w)
    (set! (.-height canvas) h)
    {:el el :stage stage :gl gl :canvas canvas :ctx (.getContext canvas "2d") :size size
     :background (opaque-background stage)}))

(defn paint!
  "Paints the frame the engine just drew (call it right after drawing, while the drawing is there)."
  [{:keys [^js el ^js stage ^js gl ^js ctx size background]}]
  (let [[vw vh] size
        ^js sr (.getBoundingClientRect stage)
        o {:left (.-left sr) :top (.-top sr)}
        {:keys [scale x y]} (video/fit (.-width sr) (.-height sr) vw vh)]
    (.setTransform ctx 1 0 0 1 0 0)
    (set! (.-globalAlpha ctx) 1)
    (set! (.-fillStyle ctx) background)
    (.fillRect ctx 0 0 vw vh)
    (.setTransform ctx scale 0 0 scale x y)
    (.drawImage ctx gl 0 0 (.-width sr) (.-height sr))
    (doseq [sel overlays]
      (when-let [o-el (.querySelector el sel)] (paint/el! ctx o-el o 1)))))

(defn jpeg
  "Promise of the canvas's picture as a JPEG, base64."
  [{:keys [^js canvas]}]
  (js/Promise.
   (fn [done fail]
     (.toBlob canvas
              (fn [^js blob]
                (if-not blob
                  (fail (js/Error. "the frame could not be encoded"))
                  (let [r (js/FileReader.)]
                    (set! (.-onload r) #(done (second (re-find #",(.*)$" (.-result r)))))
                    (set! (.-onerror r) #(fail (.-error r)))
                    (.readAsDataURL r blob))))
              "image/jpeg" 0.92))))
