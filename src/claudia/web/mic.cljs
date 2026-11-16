(ns claudia.web.mic
  "Recording for the composer's mic. The microphone opens at the first press and then stays warm:
  an AudioWorklet (mic-tap.js) hands its samples over all the time, the last second is kept, and a
  press starts the clip half a second back, so the first words are never lost to the microphone
  waking up. It closes after `idle-ms` without a press, or as soon as the page is hidden (the
  browser shows the microphone in use while it is warm). The clip goes to the hub as 16 kHz WAV
  (POST /hub/listen, claudia.hub.listen); an analyser drives the bars."
  (:require [claudia.web.api :as api]
            [claudia.web.pcm :as pcm]))

(def ^:private idle-ms "How long the microphone stays warm after the last press." (* 10 60 1000))
(def ^:private keep-s "How much sound before now is kept." 1)
(def ^:private pre-s "How far back a clip starts from the press." 0.5)
(def ^:private late-ms "Waited at release for the samples still on their way from the worklet." 80)

;; nil, or {:ctx :stream :analyser :node :ring [chunks] :clip [chunks]-or-nil :timer}
(defonce ^:private warm (atom nil))
(defonce ^:private opening (atom nil))

(defonce ^:private listen?
  ;; Whether the hub has a listen service (asked once per page).
  (delay (-> (api/fetch-json "/hub/listen")
             (.then #(boolean (:listen %)))
             (.catch (fn [_] false)))))

(defn available?
  "Promise of whether the mic can work here: a microphone API, AudioWorklet and the hub's listen service."
  []
  (if (and (exists? js/AudioWorkletNode) (some-> js/navigator .-mediaDevices .-getUserMedia))
    @listen?
    (js/Promise.resolve false)))

(defn heard
  "The warm state after a piece of samples: into the rolling buffer, and into the clip while one runs."
  [w piece limit]
  (cond-> (update w :ring pcm/ring-push piece limit)
    (:clip w) (update :clip conj piece)))

(defn- rate [w] (.-sampleRate ^js (:ctx w)))

(defn- on-chunk [^js e]
  (swap! warm #(when % (heard % (.-data e) (* keep-s (rate %))))))

(defn- close! []
  (when-let [{:keys [^js stream ^js ctx timer]} @warm]
    (reset! warm nil)
    (some-> timer js/clearTimeout)
    (doseq [^js track (.getTracks stream)] (.stop track))
    (.catch (.close ctx) (fn [_] nil))))

(defn- analyser-of [^js ctx ^js source]
  (let [an (.createAnalyser ctx)]
    (set! (.-fftSize an) 512)
    (set! (.-smoothingTimeConstant an) 0.5)
    (.connect source an)
    an))

(defn- wire
  "The warm state for a context and a microphone stream: the tap and the analyser on it."
  [^js ctx ^js stream]
  (let [source (.createMediaStreamSource ctx stream)
        node (js/AudioWorkletNode. ctx "claudia-mic-tap")]
    (set! (.. node -port -onmessage) on-chunk)
    (.connect source node)
    {:ctx ctx :stream stream :analyser (analyser-of ctx source) :node node :ring [] :clip nil}))

(defn- open!
  "Promise of the warm state, opening the microphone when it is cold. The context is made first,
  inside the press (iOS lets audio run only from a person's gesture)."
  []
  (or (some-> @warm js/Promise.resolve)
      @opening
      (let [ctx (js/AudioContext.)
            p (-> (js/Promise.all #js [(.resume ctx)
                                       (.addModule (.-audioWorklet ctx) "/mic-tap.js")
                                       (.getUserMedia (.-mediaDevices js/navigator)
                                                      #js {:audio #js {:echoCancellation true :noiseSuppression true}})])
                  (.then (fn [[_ _ stream]] (reset! warm (wire ctx stream))))
                  (.catch (fn [e] (.catch (.close ctx) (fn [_] nil)) (throw e)))
                  (.finally #(reset! opening nil)))]
        (reset! opening p)
        p)))

(defn began
  "The warm state as a clip starts: the clip holds the last pre samples, and no close is due."
  [w pre]
  (assoc w :clip (pcm/tail (:ring w) pre) :timer nil))

(defn- cool!
  "The clip ends: the microphone stays warm for idle-ms more."
  []
  (swap! warm #(when % (assoc % :clip nil :timer (js/setTimeout close! idle-ms)))))

(defn start!
  "Promise of a recording session: the microphone warm and the clip running (from pre-s back)."
  []
  (.then (open!)
         (fn [w]
           (some-> (:timer w) js/clearTimeout)
           (swap! warm began (js/Math.round (* pre-s (rate w))))
           {:analyser (:analyser @warm)})))

(defn discard! "Ends a session, keeping nothing." [_session] (cool!))

(defn- wav-of [chunks from]
  (js/Blob. #js [(pcm/wav (pcm/resampled (pcm/joined chunks) from pcm/rate) pcm/rate)] #js {:type "audio/wav"}))

(defn stop!
  "Promise of the session's clip (a WAV Blob), once the last samples arrived; the microphone stays warm."
  [_session]
  (js/Promise.
   (fn [done _]
     (js/setTimeout (fn []
                      (let [w @warm]
                        (cool!)
                        (done (wav-of (or (:clip w) []) (if w (rate w) pcm/rate)))))
                    late-ms))))

(defonce ^:private closes-when-hidden
  ;; A hidden page lets the microphone go (on an iPhone the system takes it anyway).
  (when (exists? js/document)
    (js/document.addEventListener "visibilitychange" #(when (= "hidden" js/document.visibilityState) (close!)))))

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
