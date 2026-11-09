(ns claudia.web.capture
  "The picture of a circle: a frame of this tab, cropped. The tab is shared once per page load
  (Chrome's \"this tab\" prompt, preferCurrentTab) and the stream stays open, so later circles take a
  frame without asking again; when the user stops sharing, the next circle asks again."
  (:require [claudia.web.circle :as circle]))

;; {:stream MediaStream :video <video> playing it}, or nil before the first circle.
(defonce ^:private shared (atom nil))

(defn- live? [{:keys [^js stream]}]
  (boolean (and stream (some #(= "live" (.-readyState ^js %)) (.getVideoTracks stream)))))

(defn- ask-stream
  "Promise of the tab's stream; the browser asks the user (it needs a recent press)."
  []
  (let [dpr (or js/window.devicePixelRatio 1)]
    (.getDisplayMedia js/navigator.mediaDevices
                      #js {:video #js {:displaySurface "browser"
                                       :width #js {:ideal (* dpr js/window.innerWidth)}
                                       :height #js {:ideal (* dpr js/window.innerHeight)}
                                       :frameRate #js {:ideal 30}}
                           :audio false
                           :preferCurrentTab true
                           :selfBrowserSurface "include"
                           :surfaceSwitching "exclude"
                           :monitorTypeSurfaces "exclude"})))

(defn- playing
  "Promise of a muted, hidden video element playing stream."
  [^js stream]
  (let [v (js/document.createElement "video")]
    (set! (.-muted v) true)
    (set! (.-playsInline v) true)
    (set! (.-srcObject v) stream)
    (.then (.play v) (fn [] v))))

(defn- ready
  "Promise of the shared {:stream :video}, asking for the tab when there is none (or it ended)."
  []
  (let [s @shared]
    (if (live? s)
      (js/Promise.resolve s)
      (-> (ask-stream)
          (.then (fn [stream] (.then (playing stream) (fn [v] (reset! shared {:stream stream :video v})))))))))

(defn- next-frame
  "Promise resolved once the video shows a frame painted after now (the chalk line is in it)."
  [^js v]
  (js/Promise.
   (fn [ok]
     (js/requestAnimationFrame
      (fn [] (js/requestAnimationFrame
              (fn [] (if (.-requestVideoFrameCallback v)
                       (.requestVideoFrameCallback v (fn [] (.requestVideoFrameCallback v (fn [] (ok v)))))
                       (js/setTimeout #(ok v) 120)))))))))

(defn- crop-blob
  "Promise of a PNG Blob of the window box `zone` cut from the video's frame."
  [^js v zone]
  (let [{:keys [x y w h]} (circle/crop zone (/ (.-videoWidth v) js/window.innerWidth) (/ (.-videoHeight v) js/window.innerHeight))
        canvas (js/document.createElement "canvas")]
    (set! (.-width canvas) w)
    (set! (.-height canvas) h)
    (.drawImage (.getContext canvas "2d") v x y w h 0 0 w h)
    (js/Promise. (fn [ok no] (.toBlob canvas #(if % (ok %) (no (js/Error. "The frame could not be read."))) "image/png")))))

(defn shot
  "Promise of a PNG File of the window box (zone-of) returns, read once the tab's frame is current:
  sharing starts the first time, and its bar can move the page, so the box is measured late."
  [zone-of]
  (-> (ready)
      (.then (fn [{:keys [video]}] (next-frame video)))
      (.then (fn [v]
               (if-let [zone (zone-of)]
                 (crop-blob v zone)
                 (throw (js/Error. "The circle is out of sight: scroll it back and circle again.")))))
      (.then (fn [blob] (js/File. #js [blob] "circled.png" #js {:type "image/png"})))))
