(ns claudia.web.voice
  "The hub's voice, for everything on the page that speaks (a 3D story's narrator, talking mode):
  POST /hub/narrate turns text into raw 16-bit mono PCM at 24 kHz.")

(def rate "The voice's samples per second." 24000)

(defonce ^:private available
  ;; Whether the hub has a voice set up (asked once per page).
  (delay (-> (js/fetch "/hub/narrate" #js {:credentials "same-origin"})
             (.then (fn [^js r] (if (.-ok r) (.json r) #js {})))
             (.then (fn [^js j] (boolean (.-voice j))))
             (.catch (fn [_] false)))))

(defn available? "Promise of whether the hub has a voice." [] @available)

(defn- answer-buffer
  "The hub's answer: the PCM bytes, or a rejection with its error."
  [^js r]
  (if (.-ok r)
    (.arrayBuffer r)
    (-> (.json r)
        (.catch (fn [_] #js {}))
        (.then (fn [^js j] (throw (js/Error. (or (.-error j) (str "voice answered " (.-status r))))))))))

(defn fetch-pcm
  "Promise of the PCM bytes (an ArrayBuffer) for text, in the voice described by instruction (nil:
  the hub's own)."
  [text instruction]
  (-> (js/fetch "/hub/narrate"
                #js {:method "POST" :credentials "same-origin"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify #js {:text text :instruction instruction})})
      (.then answer-buffer)))

(defn audio-buffer
  "16-bit PCM bytes as an AudioBuffer of ctx."
  [^js ctx ^js buf]
  (let [pcm (js/Int16Array. buf 0 (js/Math.floor (/ (.-byteLength buf) 2)))
        ^js b (.createBuffer ctx 1 (.-length pcm) rate)
        out (.getChannelData b 0)]
    (dotimes [i (.-length pcm)] (aset out i (/ (aget pcm i) 32768)))
    b))
