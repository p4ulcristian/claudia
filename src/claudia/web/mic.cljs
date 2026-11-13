(ns claudia.web.mic
  "Recording for the composer's mic: the microphone while the button is held, as the browser
  records it (WebM/Opus, or MP4 in Safari and the iPhone app), with an analyser for the bars; the
  hub turns a clip into text (POST /hub/listen, claudia.hub.listen)."
  (:require [claudia.web.api :as api]))

(def ^:private types ["audio/webm;codecs=opus" "audio/mp4" "audio/ogg;codecs=opus"])

(defonce ^:private listen?
  ;; Whether the hub has a listen service (asked once per page).
  (delay (-> (api/fetch-json "/hub/listen")
             (.then #(boolean (:listen %)))
             (.catch (fn [_] false)))))

(defn available?
  "Promise of whether the mic can work here: a recorder, a microphone API and the hub's listen service."
  []
  (if (and (exists? js/MediaRecorder) (some-> js/navigator .-mediaDevices .-getUserMedia))
    @listen?
    (js/Promise.resolve false)))

(defn- record-type []
  (or (first (filter #(.isTypeSupported js/MediaRecorder %) types)) ""))

(defn- analyser-of [^js stream]
  (let [ctx (js/AudioContext.)
        an (.createAnalyser ctx)]
    (set! (.-fftSize an) 512)
    (set! (.-smoothingTimeConstant an) 0.5)
    (.connect (.createMediaStreamSource ctx stream) an)
    {:ctx ctx :analyser an}))

(defn start!
  "Promise of a recording session: the microphone on and recording."
  []
  (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio #js {:echoCancellation true :noiseSuppression true}})
      (.then (fn [^js stream]
               (let [t (record-type)
                     rec (js/MediaRecorder. stream (if (seq t) #js {:mimeType t} #js {}))
                     chunks #js []]
                 (.addEventListener rec "dataavailable" #(when (pos? (.. % -data -size)) (.push chunks (.-data %))))
                 (.start rec)
                 (merge {:stream stream :recorder rec :chunks chunks} (analyser-of stream)))))))

(defn- close! [{:keys [^js stream ^js ctx]}]
  (doseq [^js track (.getTracks stream)] (.stop track))
  (.catch (.close ctx) (fn [_] nil)))

(defn discard! "Stops a session, keeping nothing." [{:keys [^js recorder] :as session}]
  (when (not= "inactive" (.-state recorder)) (.stop recorder))
  (close! session))

(defn stop!
  "Promise of the session's clip (a Blob); the microphone goes off."
  [{:keys [^js recorder chunks] :as session}]
  (js/Promise.
   (fn [done _]
     (.addEventListener recorder "stop"
                        (fn [_]
                          (close! session)
                          (done (js/Blob. chunks #js {:type (.-mimeType recorder)}))))
     (.stop recorder))))

(defn spectrum
  "The voice's spectrum now, bytes 0-255 low to high, as a vector."
  [{:keys [^js analyser]}]
  (let [a (js/Uint8Array. (.-frequencyBinCount analyser))]
    (.getByteFrequencyData analyser a)
    (vec (array-seq a))))

(defn hear
  "Promise of the text the hub heard in a clip."
  [clip]
  (.then (api/post-blob "/hub/listen" clip) #(or (:text %) "")))
