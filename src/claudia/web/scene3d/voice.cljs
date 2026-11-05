(ns claudia.web.scene3d.voice
  "The narrator of a scene3d story: the hub turns each line into speech (POST /hub/narrate: raw 16-bit
  mono PCM at 24 kHz, kept on disk by the text); this plays it on the Web Audio clock, so a clip's
  length is known exactly and pausing pauses the voice with the story.")

(def ^:private rate 24000)

(defonce ^:private available
  ;; Whether the hub has a voice set up (asked once per page).
  (delay (-> (js/fetch "/hub/narrate" #js {:credentials "same-origin"})
             (.then (fn [^js r] (if (.-ok r) (.json r) #js {})))
             (.then (fn [^js j] (boolean (.-voice j))))
             (.catch (fn [_] false)))))

(defn available? [] @available)

(defn- context! "The audio context, made on first use." [{:keys [ctx]}]
  (or @ctx (reset! ctx (js/AudioContext. #js {:sampleRate rate}))))

(defn- answer-buffer
  "The hub's answer: the PCM bytes, or a rejection with its error."
  [^js r]
  (if (.-ok r)
    (.arrayBuffer r)
    (-> (.json r)
        (.catch (fn [_] #js {}))
        (.then (fn [^js j] (throw (js/Error. (or (.-error j) (str "voice answered " (.-status r))))))))))

(defn- clip-of "16-bit PCM bytes as a clip {:buffer AudioBuffer :seconds n}." [voice ^js buf]
  (let [pcm (js/Int16Array. buf 0 (js/Math.floor (/ (.-byteLength buf) 2)))
        ^js b (.createBuffer ^js (context! voice) 1 (.-length pcm) rate)
        out (.getChannelData b 0)]
    (dotimes [i (.-length pcm)] (aset out i (/ (aget pcm i) 32768)))
    {:buffer b :seconds (/ (.-length pcm) rate)}))

(defn- fetch-clip "A promise of a line's clip, or nil (the error kept)." [{:keys [error] :as voice} text instruction]
  (-> (js/fetch "/hub/narrate"
                #js {:method "POST" :credentials "same-origin"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify #js {:text text :instruction instruction})})
      (.then answer-buffer)
      (.then #(clip-of voice %))
      (.catch (fn [e] (reset! error (.-message e)) nil))))

(defn- load! "A line's clip, fetched once per text and instruction." [{:keys [clips] :as voice} text instruction]
  (let [k [instruction text]]
    (or (get @clips k)
        (let [p (fetch-clip voice text instruction)] (swap! clips assoc k p) p))))

(defn- stop! [{:keys [source]}]
  (when-let [^js s @source] (try (.stop s) (catch :default _ nil)))
  (reset! source nil))

(defn- play! [{:keys [source tap] :as voice} {:keys [buffer]}]
  (stop! voice)
  (let [^js c (context! voice)]
    (-> (if (= "suspended" (.-state c)) (.catch (.resume c) (fn [_] nil)) (js/Promise.resolve nil))
        (.then (fn [_]
                 (let [^js src (.createBufferSource c)]
                   (set! (.-buffer src) buffer)
                   (.connect src (.-destination c))
                   (when-let [{:keys [dest]} @tap] (.connect src dest))
                   (.start src)
                   (reset! source src)))))))

(defn- close! [{:keys [ctx] :as voice}]
  (stop! voice)
  (some-> ^js @ctx .close)
  (reset! ctx nil))

(defn- hum!
  "A looped noise about 70 dB below full scale (not heard) into node d, so the recording's sound
  never goes silent: the browser's encoder leaves silent stretches out of the file, and players
  that do not keep the timestamps then slip the voice against the picture. -> the playing source."
  [^js c ^js d]
  (let [n (.-sampleRate c)
        ^js b (.createBuffer c 1 n n)
        ch (.getChannelData b 0)
        ^js src (.createBufferSource c)]
    (dotimes [i n] (aset ch i (* 0.0006 (- (js/Math.random) 0.5))))
    (set! (.-buffer src) b)
    (set! (.-loop src) true)
    (.connect src d)
    (.start src)
    src))

(defn- tap!
  "From now on every clip also plays into a stream (for a recording), over a hum that keeps it
  going. -> that MediaStream."
  [{:keys [tap] :as voice}]
  (let [^js c (context! voice)
        ^js d (.createMediaStreamDestination c)]
    (.catch (.resume c) (fn [_] nil))
    (reset! tap {:dest d :hum (hum! c d)})
    (.-stream d)))

(defn- untap! [{:keys [tap]}]
  (when-let [{:keys [^js hum]} @tap] (.stop hum))
  (reset! tap nil))

(defn make
  "One story's voice: {:load (fn [text instruction] promise of clip or nil) :play :stop :pause
  :resume :close :tap (-> a MediaStream every clip also plays into) :untap :error (atom)}. A clip
  is {:buffer AudioBuffer :seconds n}."
  []
  (let [{:keys [ctx error] :as voice} {:ctx (atom nil) :clips (atom {}) :source (atom nil) :tap (atom nil)
                                           :error (atom "")}]
    {:error error
     :load #(load! voice %1 %2)
     :play #(play! voice %)
     :stop #(stop! voice)
     :pause (fn [] (some-> ^js @ctx .suspend))
     :resume (fn [] (some-> ^js @ctx .resume))
     :close #(close! voice)
     :tap #(tap! voice)
     :untap #(untap! voice)}))
