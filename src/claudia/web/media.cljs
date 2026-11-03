(ns claudia.web.media
  "Photos and videos the user attaches (picker, paste, drop), turned into what Claude reads: images.
  A photo goes as it is when Claude takes it (JPEG, PNG, GIF, WebP, small enough), else redrawn as a
  JPEG of at most 2048 px. Claude reads no video, so a video goes as frames drawn here, at most 30,
  spread evenly (one per second for short ones), with a note before them saying what they are.
  Everything else (and a photo the browser can't draw, HEIC, SVG) is uploaded as a file."
  (:require [clojure.string :as str]))

(def ^:private taken #{"image/jpeg" "image/png" "image/gif" "image/webp"})
(def ^:private photo-edge 2048)
(def ^:private photo-bytes (* 3.5 1024 1024))
(def ^:private frame-edge 1280)
(def ^:private max-frames 30)

(defn- data-of [url] (subs url (inc (str/index-of url ","))))

(defn- draw [src w h edge quality]
  (let [k (min 1 (/ edge (max w h)))
        canvas (js/document.createElement "canvas")]
    (set! (.-width canvas) (max 1 (js/Math.round (* w k))))
    (set! (.-height canvas) (max 1 (js/Math.round (* h k))))
    (.drawImage (.getContext canvas "2d") src 0 0 (.-width canvas) (.-height canvas))
    (.toDataURL canvas "image/jpeg" quality)))

(defn- read-url [blob]
  (js/Promise. (fn [ok no]
                 (let [r (js/FileReader.)]
                   (set! (.-onload r) #(ok (str (.-result r))))
                   (set! (.-onerror r) #(no (.-error r)))
                   (.readAsDataURL r blob)))))

(defn- photo [^js f]
  (-> (js/createImageBitmap f)
      (.then (fn [^js bm]
               (-> (if (and (taken (.-type f)) (<= (.-size f) photo-bytes) (<= (max (.-width bm) (.-height bm)) photo-edge))
                     (.then (read-url f) (fn [url] {:url url :images [{:media_type (.-type f) :data (data-of url)}]}))
                     (let [url (draw bm (.-width bm) (.-height bm) photo-edge 0.9)]
                       (js/Promise.resolve {:url url :images [{:media_type "image/jpeg" :data (data-of url)}]})))
                   (.finally #(.close bm)))))))

(defn- once [^js el ok-event]
  (js/Promise. (fn [resolve reject]
                 (letfn [(done [] (off) (resolve nil))
                         (fail [] (off) (reject (or (.-error el) (js/Error. "video"))))
                         (off [] (.removeEventListener el ok-event done) (.removeEventListener el "error" fail))]
                   (.addEventListener el ok-event done)
                   (.addEventListener el "error" fail)))))

(defn- at [s] (str (.toFixed s 1) " s"))

(defn- video [^js f]
  (let [src (js/URL.createObjectURL f)
        v (js/document.createElement "video")]
    (set! (.-muted v) true)
    (set! (.-playsInline v) true)
    (set! (.-preload v) "auto")
    (set! (.-src v) src)
    (-> (once v "loadeddata")
        ;; iOS Safari draws nothing from a video that has never played: start it once, silently.
        (.then #(-> (.play v) (.then (fn [] (.pause v)) (fn [_] nil))))
        (.then (fn []
                 (let [seconds (.-duration v)
                       n (max 1 (min max-frames (js/Math.ceil seconds)))
                       times (for [i (range n)] (min (/ (* seconds (+ i 0.5)) n) (max 0 (- seconds 0.05))))]
                   (.then (reduce (fn [p t]
                                    (.then p (fn [acc]
                                               (set! (.-currentTime v) t)
                                               (.then (once v "seeked")
                                                      (fn [] (conj acc (draw v (.-videoWidth v) (.-videoHeight v) frame-edge 0.8)))))))
                                  (js/Promise.resolve []) times)
                          (fn [frames]
                            (let [note (str "Video \"" (.-name f) "\" (" (at seconds) ", " (.-videoWidth v) "x" (.-videoHeight v) "): "
                                            n " frame" (when (> n 1) "s") " follow in order, taken at "
                                            (str/join ", " (map at times)) ". There is no sound.")]
                              {:url (first frames)
                               :note note
                               :video seconds
                               :images (mapv (fn [u] {:media_type "image/jpeg" :data (data-of u)}) frames)}))))))
        (.finally (fn []
                    (.removeAttribute v "src")
                    (.load v)
                    (js/URL.revokeObjectURL src))))))

(defn read-media
  "Promise of {:url (thumbnail) :images [{media_type data}] :note? :video?} for a photo or video, or
  of nil (not one, or the browser can't read it: it goes as a file)."
  [^js f]
  (let [t (str (.-type f))]
    (-> (cond (and (str/starts-with? t "image/") (not (#{"image/heic" "image/heif" "image/svg+xml"} t))) (photo f)
              (str/starts-with? t "video/") (video f)
              :else (js/Promise.resolve nil))
        (.catch (fn [e] (js/console.error "attach" (.-name f) e) nil)))))
