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

(defn make
  "One story's voice: {:load (fn [text instruction] promise of clip or nil) :play :stop :pause
  :resume :close :error (atom)}. A clip is {:buffer AudioBuffer :seconds n}."
  []
  (let [ctx (atom nil)
        clips (atom {})
        source (atom nil)
        error (atom "")
        context (fn [] (or @ctx (reset! ctx (js/AudioContext. #js {:sampleRate rate}))))
        fetch-clip (fn [text instruction]
                     (-> (js/fetch "/hub/narrate"
                                   #js {:method "POST" :credentials "same-origin"
                                        :headers #js {"content-type" "application/json"}
                                        :body (js/JSON.stringify #js {:text text :instruction instruction})})
                         (.then (fn [^js r]
                                  (if (.-ok r)
                                    (.arrayBuffer r)
                                    (-> (.json r)
                                        (.catch (fn [_] #js {}))
                                        (.then (fn [^js j] (throw (js/Error. (or (.-error j) (str "voice answered " (.-status r)))))))))))
                         (.then (fn [buf]
                                  (let [pcm (js/Int16Array. buf 0 (js/Math.floor (/ (.-byteLength buf) 2)))
                                        ^js b (.createBuffer ^js (context) 1 (.-length pcm) rate)
                                        out (.getChannelData b 0)]
                                    (dotimes [i (.-length pcm)] (aset out i (/ (aget pcm i) 32768)))
                                    {:buffer b :seconds (/ (.-length pcm) rate)})))
                         (.catch (fn [e] (reset! error (.-message e)) nil))))
        stop! (fn [] (when-let [^js s @source] (try (.stop s) (catch :default _ nil))) (reset! source nil))]
    {:error error
     :load (fn [text instruction]
             (let [k [instruction text]]
               (or (get @clips k)
                   (let [p (fetch-clip text instruction)] (swap! clips assoc k p) p))))
     :play (fn [{:keys [buffer]}]
             (stop!)
             (let [^js c (context)]
               (-> (if (= "suspended" (.-state c)) (.catch (.resume c) (fn [_] nil)) (js/Promise.resolve nil))
                   (.then (fn [_]
                            (let [^js src (.createBufferSource c)]
                              (set! (.-buffer src) buffer)
                              (.connect src (.-destination c))
                              (.start src)
                              (reset! source src)))))))
     :stop stop!
     :pause (fn [] (some-> ^js @ctx .suspend))
     :resume (fn [] (some-> ^js @ctx .resume))
     :close (fn [] (stop!) (some-> ^js @ctx .close) (reset! ctx nil))}))
