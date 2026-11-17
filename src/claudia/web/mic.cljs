(ns claudia.web.mic
  "Recording for the composer's mic. The microphone opens at the first press and then stays warm:
  an AudioWorklet (mic-tap.js) hands its samples over all the time, the last second is kept, and a
  press starts the clip half a second back, so the first words are never lost to the microphone
  waking up. It closes after `idle-ms` without a press, or as soon as the page is hidden (the
  browser shows the microphone in use while it is warm). The clip goes to the hub as 16 kHz WAV
  (POST /hub/listen, claudia.hub.listen); an analyser drives the bars. The microphone opens without
  echo cancellation and noise suppression (`constraints`): with them, iOS puts the whole page into
  call audio while the microphone is warm, whose own quieter volume ignores the media volume (bells
  and the read-aloud voice went faint on an iPad at full volume). Nothing needs the echo removed:
  the read-aloud voice stops when the mic is held.

  A microphone can be open and still give no sound (on an iPad: another Claudia window or another
  app holds it, or an interruption stopped it). So the samples are watched: a fresh microphone must
  give its first ones within `first-ms`, a warm one whose last ones are older than `stall-ms` is
  opened again at the press, a track that ends or mutes or a context that stops running lets the
  microphone go, a page that opens the microphone tells the other Claudia pages to let theirs go
  (BroadcastChannel), and a clip without a sample is an error, never sent. An opening that gives no
  sound is tried again, fresh, up to `max-tries` times in the same press before the press fails.
  In the iPhone/iPad app the app records instead (claudia.web.mic-native, README decision 41)."
  (:require [claudia.web.api :as api]
            [claudia.web.mic-native :as native]
            [claudia.web.pcm :as pcm]))

(def ^:private idle-ms "How long the microphone stays warm after the last press." (* 10 60 1000))
(def ^:private keep-s "How much sound before now is kept." 1)
(def ^:private constraints #js {:audio #js {:echoCancellation false :noiseSuppression false}})
(def ^:private pre-s "How far back a clip starts from the press." 0.5)
(def ^:private late-ms "Waited at release for the samples still on their way from the worklet." 80)
(def ^:private stall-ms "A warm microphone whose last samples are older than this gives no sound." 400)
(def ^:private first-ms "How long a fresh microphone may take to give its first samples." 2000)
(def ^:private max-tries "Fresh openings one press makes while the microphone gives no sound." 3)
(def no-sound "The microphone gives no sound (another Claudia window or app may hold it): try again.")

;; nil, or {:ctx :stream :analyser :node :ring [chunks] :clip [chunks]-or-nil :timer :last ms}
(defonce ^:private warm (atom nil))
(defonce ^:private opening (atom nil))
;; When the last opening began and how far each step got (ms after it began): for the mic log.
(defonce ^:private steps (atom nil))

(defn- step! [k] (swap! steps #(when % (assoc % k (- (js/Date.now) (:at %))))))

(defn steps-now
  "How far the last opening got: {:mic :module :running ms after it began}, or nil."
  []
  (some-> @steps (dissoc :at)))

(defn warm? "Whether the microphone is warm now." [] (if (native/here?) (native/warm?) (some? @warm)))

(defn- native-app []
  (when-let [n (.-claudiaNative js/window)] (str (.-platform n) " " (.-version n))))

(defn report!
  "Tells the hub's log what a press did (POST /hub/listen/log): no sound, no words, only how it went."
  [m]
  (-> (api/post-json "/hub/listen/log" (assoc m :ua (.-userAgent js/navigator) :app (native-app)))
      (.catch (fn [_] nil))))

(defonce ^:private listen?
  ;; Whether the hub has a listen service (asked once per page).
  (delay (-> (api/fetch-json "/hub/listen")
             (.then #(boolean (:listen %)))
             (.catch (fn [_] false)))))

(defn available?
  "Promise of whether the mic can work here: a microphone API, AudioWorklet and the hub's listen service."
  []
  (if (or (native/here?)
          (and (exists? js/AudioWorkletNode) (some-> js/navigator .-mediaDevices .-getUserMedia)))
    @listen?
    (js/Promise.resolve false)))

(defn heard
  "The warm state after a piece of samples arrived at now (ms): into the rolling buffer, and into
  the clip while one runs."
  [w piece limit now]
  (cond-> (-> w (update :ring pcm/ring-push piece limit) (assoc :last now))
    (:clip w) (update :clip conj piece)))

(defn sounding?
  "Whether the warm state w had samples within stall-ms before now (ms)."
  [w now]
  (boolean (some->> (:last w) (- now) (> stall-ms))))

(defn- rate [w] (.-sampleRate ^js (:ctx w)))

(defn- on-chunk [^js e]
  (swap! warm #(when % (heard % (.-data e) (* keep-s (rate %)) (js/Date.now)))))

(defn- close! []
  (when-let [{:keys [^js stream ^js ctx timer]} @warm]
    (reset! warm nil)
    (some-> timer js/clearTimeout)
    (doseq [^js track (.getTracks stream)] (.stop track))
    (.catch (.close ctx) (fn [_] nil))))

(defn- close-stream!
  "Lets the microphone go when stream is still the warm one (a late event of an old one changes nothing)."
  [stream]
  (when (identical? stream (:stream @warm)) (close!)))

(defn- watch!
  "Lets the microphone go when its track ends or mutes, or its context stops running."
  [^js ctx ^js stream]
  (doseq [^js track (.getAudioTracks stream)]
    (.addEventListener track "ended" #(close-stream! stream))
    (.addEventListener track "mute" #(close-stream! stream)))
  (set! (.-onstatechange ctx) #(when (not= "running" (.-state ctx)) (close-stream! stream))))

(defn- state-text
  "How the warm microphone stands, for the mic log: its track and its context."
  []
  (if-let [{:keys [^js stream ^js ctx]} @warm]
    (let [^js t (first (.getAudioTracks stream))]
      (str "track " (some-> t .-readyState) (when (some-> t .-muted) " muted") ", context " (.-state ctx)))
    "no microphone"))

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
    (watch! ctx stream)
    {:ctx ctx :stream stream :analyser (analyser-of ctx source) :node node :ring [] :clip nil}))

(defn- in-time
  "Promise p, or a refusal with text when it has not settled within ms."
  [p ms text]
  (js/Promise.race #js [p (js/Promise. (fn [_ no] (js/setTimeout #(no (js/Error. text)) ms)))]))

(defn- warm-up
  "Promise of the warm state for a microphone stream: a context, the tap loaded, the context running.
  The context is made only now: while the page captures, WebKit lets it run without a gesture (a
  finger going down on a touch screen is not one, so resuming inside the press waits for ever)."
  [^js stream]
  (let [ctx (js/AudioContext.)]
    (-> (js/Promise.all #js [(.then (.addModule (.-audioWorklet ctx) "/mic-tap.js") #(step! :module))
                             (.then (in-time (.resume ctx) 3000 "The microphone didn't start: try again.")
                                    #(step! :running))])
        (.then #(reset! warm (wire ctx stream)))
        (.catch (fn [e]
                  (doseq [^js track (.getTracks stream)] (.stop track))
                  (.catch (.close ctx) (fn [_] nil))
                  (throw e))))))

(defn- first-sound
  "Promise of the warm state once its microphone gave samples; refused (the microphone let go) when
  none came within first-ms."
  [_]
  (let [until (+ (js/Date.now) first-ms)]
    (js/Promise.
     (fn [ok no]
       ((fn check []
          (cond
            (:last @warm) (do (step! :sound) (ok @warm))
            (and @warm (< (js/Date.now) until)) (js/setTimeout check 50)
            :else (do (report! {:event "no-sound" :error (state-text) :steps (steps-now)})
                      (close!)
                      (no (js/Error. no-sound))))))))))

(defonce ^:private pages
  ;; The other Claudia pages of this browser or app: one microphone at a time, the newest one's.
  (when (exists? js/BroadcastChannel)
    (let [^js ch (js/BroadcastChannel. "claudia-mic")]
      (set! (.-onmessage ch) #(close!))
      ch)))

(defn- open-once
  "Promise of the warm state from one fresh opening: the microphone, the context, the first samples."
  []
  (reset! steps {:at (js/Date.now)})
  (some-> ^js pages (.postMessage "taken"))
  (-> (in-time (.getUserMedia (.-mediaDevices js/navigator) constraints)
               15000 "The microphone didn't answer: try again.")
      (.then (fn [stream] (step! :mic) (warm-up stream)))
      (.then first-sound)))

(defn retry?
  "Whether an opening that failed with err-text, after tries openings, gets another one: only a
  microphone that gave no sound does, up to `tries` openings."
  [err-text tries]
  (and (= err-text no-sound) (< tries max-tries)))

(defn- open-trying
  "Promise of the warm state, opening again (fresh) while the microphone gives no sound."
  [tries]
  (.catch (open-once)
          (fn [e]
            (if (retry? (.-message e) tries)
              (open-trying (inc tries))
              (throw e)))))

(defn- open!
  "Promise of the warm state, opening the microphone when it is cold (one opening at a time)."
  []
  (or (some-> @warm js/Promise.resolve)
      @opening
      (let [p (.finally (open-trying 1) #(reset! opening nil))]
        (reset! opening p)
        p)))

(defn- revive!
  "Lets a warm microphone go that gives no sound and runs no clip, so the press opens it again."
  []
  (when-let [w @warm]
    (when-not (or (:clip w) (sounding? w (js/Date.now)))
      (report! {:event "silent-warm" :error (state-text)})
      (close!))))

(defn began
  "The warm state as a clip starts: the clip holds the last pre samples, and no close is due."
  [w pre]
  (assoc w :clip (pcm/tail (:ring w) pre) :timer nil))

(defn- cool!
  "The clip ends: the microphone stays warm for idle-ms more."
  []
  (swap! warm #(when % (assoc % :clip nil :timer (js/setTimeout close! idle-ms)))))

(defn- web-start!
  []
  (revive!)
  (.then (open!)
         (fn [w]
           (some-> (:timer w) js/clearTimeout)
           (swap! warm began (js/Math.round (* pre-s (rate w))))
           {:analyser (:analyser @warm) :rate (rate w)})))

(defn start!
  "Promise of a recording session: the microphone warm (and giving sound) and the clip running (from
  pre-s back). Recorded by the app when the page runs in it."
  []
  (if (native/here?) (native/start!) (web-start!)))

(defn discard! "Ends a session, keeping nothing." [session]
  (if (:native session) (native/discard! session) (cool!)))

(defn- wav-of [chunks from]
  (js/Blob. #js [(pcm/wav (pcm/resampled (pcm/joined chunks) from pcm/rate) pcm/rate)] #js {:type "audio/wav"}))

(defn- web-stop!
  "Promise of the session's clip (a WAV Blob), once the last samples arrived; the microphone stays
  warm. Refused when the clip holds no sample (the microphone gave no sound, or was let go)."
  [_session]
  (js/Promise.
   (fn [done fail]
     (js/setTimeout (fn []
                      (let [w @warm
                            chunks (or (:clip w) [])]
                        (cool!)
                        (if (pos? (pcm/size chunks))
                          (done (wav-of chunks (rate w)))
                          (do (report! {:event "empty-clip" :error (state-text)})
                              (fail (js/Error. no-sound))))))
                    late-ms))))

(defonce ^:private closes-when-hidden
  ;; A hidden page lets the microphone go (on an iPhone the system takes it anyway).
  (when (exists? js/document)
    (js/document.addEventListener "visibilitychange" #(when (= "hidden" js/document.visibilityState) (close!)))))

(defn stop!
  "Promise of the session's clip (a WAV Blob); refused when it holds no sample."
  [session]
  (if (:native session) (native/stop! session) (web-stop! session)))

(defn- web-spectrum
  [{:keys [^js analyser]}]
  (let [a (js/Uint8Array. (.-frequencyBinCount analyser))]
    (.getByteFrequencyData analyser a)
    (vec (array-seq a))))

(defn spectrum
  "The voice's spectrum now, bytes 0-255 low to high (fftSize 512: 256 of them), as a vector."
  [session]
  (if (:native session) (native/spectrum session) (web-spectrum session)))

(defn hear
  "Promise of the text the hub heard in a clip."
  [clip]
  (.then (api/post-blob "/hub/listen" clip) #(or (:text %) "")))
