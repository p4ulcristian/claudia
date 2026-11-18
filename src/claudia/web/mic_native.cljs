(ns claudia.web.mic-native
  "The composer's mic recorded by the iPhone/iPad app (NativeMic.swift, README decision 41): in the
  web view, WebKit's microphone could open live after an app switch and still give no sound, while
  the app's own audio engine is checked and started again at every press. Same shape as
  claudia.web.mic, which hands over to this namespace whenever the app is there (`here?`).")

(defn- handler []
  (some-> js/window (unchecked-get "webkit") (unchecked-get "messageHandlers") (unchecked-get "claudiaMic")))

(defn here? "Whether the page runs in the app that records." [] (some? (handler)))

(defn- ask
  "Promise of the app's answer to op (a refusal carries the app's own text)."
  [op]
  (.postMessage ^js (handler) #js {:op op}))

;; The last spectrum the app gave (bytes 0-255, low to high), and whether one is on its way.
(defonce ^:private last-spectrum (atom []))
(defonce ^:private asking? (atom false))
(defonce ^:private warm (atom false))

(defn warm? "Whether the app's microphone was warm at the last press." [] @warm)

(defn start!
  "Promise of a recording session once the app's microphone gives sound: {:native true :rate hz
  :mic {:port :name :source :location :orientation}} (mic: which microphone iOS records from)."
  []
  (reset! last-spectrum [])
  (-> (ask "start")
      (.then (fn [^js r] (reset! warm true)
               {:native true :rate (.-rate r) :mic (js->clj (.-mic r) :keywordize-keys true)}))
      (.catch (fn [e] (reset! warm false) (throw (js/Error. (or (.-message e) (str e))))))))

(defn discard! "Ends a session, keeping nothing." [_session] (.catch (ask "discard") (fn [_] nil)))

(defn- wav-blob
  "base64 WAV text as a Blob."
  [b64]
  (let [bin (js/atob b64)
        octets (js/Uint8Array. (.-length bin))]
    (dotimes [i (.-length bin)] (aset octets i (.charCodeAt bin i)))
    (js/Blob. #js [octets] #js {:type "audio/wav"})))

(defn stop!
  "Promise of the session's clip (a WAV Blob); refused when the app heard no sound."
  [_session]
  (-> (ask "stop")
      (.then wav-blob)
      (.catch (fn [e] (throw (js/Error. (or (.-message e) (str e))))))))

(defn spectrum
  "The voice's spectrum as the app last gave it (a vector of bytes); asks for the next one."
  [_session]
  (when (compare-and-set! asking? false true)
    (-> (ask "spectrum")
        (.then #(reset! last-spectrum (vec (array-seq %))))
        (.finally #(reset! asking? false))))
  @last-spectrum)

(defn rate "The app microphone's sample rate, Hz." [session] (:rate session))
