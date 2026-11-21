(ns claudia.web.voice
  "The hub's voice, for everything on the page that speaks (a 3D story's narrator, talking mode):
  POST /hub/narrate turns text into raw 16-bit mono PCM at 24 kHz, sent a sentence at a time while
  it is made (stream-pcm plays it as it comes; fetch-pcm waits for all of it).")

(def rate "The voice's samples per second." 24000)

(defonce ^:private available
  ;; Whether the hub has a voice set up (asked once per page).
  (delay (-> (js/fetch "/hub/narrate" #js {:credentials "same-origin"})
             (.then (fn [^js r] (if (.-ok r) (.json r) #js {})))
             (.then (fn [^js j] (boolean (.-voice j))))
             (.catch (fn [_] false)))))

(defn available? "Promise of whether the hub has a voice." [] @available)

(defn- answer-error
  "A rejection with the hub's error answer."
  [^js r]
  (-> (.json r)
      (.catch (fn [_] #js {}))
      (.then (fn [^js j] (throw (js/Error. (or (.-error j) (str "voice answered " (.-status r)))))))))

(defn- answer-buffer
  "The hub's answer: the PCM bytes, or a rejection with its error."
  [^js r]
  (if (.-ok r) (.arrayBuffer r) (answer-error r)))

(defn- asked
  "The POST for text in the voice described by instruction, spoken in language, cancelled by
  signal (nil: never)."
  [text instruction language signal]
  (js/fetch "/hub/narrate"
            #js {:method "POST" :credentials "same-origin" :signal signal
                 :headers #js {"content-type" "application/json"}
                 :body (js/JSON.stringify #js {:text text :instruction instruction :language language})}))

(defn fetch-pcm
  "Promise of the PCM bytes (an ArrayBuffer) for text, in the voice described by instruction (nil:
  the hub's own), spoken in language (an ISO code like \"hu\"; nil: none asked), once it is all
  there."
  ([text instruction] (fetch-pcm text instruction nil))
  ([text instruction language]
   (.then (asked text instruction language nil) answer-buffer)))

(defn split-even
  "carry (the odd byte the last piece ended on, or none) then part (a chunk of the answer), cut
  after the last whole 16-bit sample -> [ArrayBuffer of whole samples, Uint8Array of the byte left
  over (0 or 1)]. The hub's chunks may end mid-sample."
  [^js carry ^js part]
  (let [n (+ (.-length carry) (.-length part))
        whole (- n (mod n 2))
        joined (js/Uint8Array. n)]
    (.set joined carry 0)
    (.set joined part (.-length carry))
    [(.slice (.-buffer joined) 0 whole) (.slice joined whole)]))

(defn read-pieces
  "Reads the answer body to its end, handing on-piece each run of whole samples as it comes ->
  Promise resolved once the last one came."
  [^js reader on-piece]
  (letfn [(step [carry]
            (.then (.read reader)
                   (fn [^js r]
                     (when-not (.-done r)
                       (let [[^js whole more] (split-even carry (.-value r))]
                         (when (pos? (.-byteLength whole)) (on-piece whole))
                         (step more))))))]
    (step (js/Uint8Array. 0))))

(defn stream-pcm
  "Like fetch-pcm, but on-piece gets the PCM (ArrayBuffers of whole samples) as it arrives, a
  sentence at a time while the hub is still making the rest -> Promise resolved after the last
  piece, rejected when the answer fails or breaks off. signal (an AbortSignal) cancels the fetch
  and its reading."
  [text instruction language signal on-piece]
  (-> (asked text instruction language signal)
      (.then (fn [^js r] (if (.-ok r) (read-pieces (.getReader (.-body r)) on-piece) (answer-error r))))))

(defn audio-buffer
  "16-bit PCM bytes as an AudioBuffer of ctx."
  [^js ctx ^js buf]
  (let [pcm (js/Int16Array. buf 0 (js/Math.floor (/ (.-byteLength buf) 2)))
        ^js b (.createBuffer ctx 1 (.-length pcm) rate)
        out (.getChannelData b 0)]
    (dotimes [i (.-length pcm)] (aset out i (/ (aget pcm i) 32768)))
    b))
