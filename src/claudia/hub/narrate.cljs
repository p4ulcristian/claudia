(ns claudia.hub.narrate
  "Narration for 3D stories (the scene3d form's narrator): one voice for every server. A line of text
  becomes speech through a voice service, kept on disk by its text (HUB_DATA/narration), so a story
  replays without asking again. The service: HUB_TTS_URL, its token in HUB_TTS_TOKEN_FILE (or
  HUB_TTS_TOKEN). Its speech route <url>/v1/audio/speech takes form fields: text alone (the
  service's default voice), or text with ref_audio and ref_text (a cloned voice); it answers raw
  16-bit mono PCM at 24 kHz.

  Asked for a voice only by a description (a story's `voice`), the service invents a slightly
  different person for every line, so a description makes ONE reference clip (kept on disk) and
  every line is spoken in a clone of it.

  GET /hub/narrate -> {voice: bool}; POST /hub/narrate {text, instruction?} -> the PCM
  (audio/L16;rate=24000;channels=1, x-seconds), or {error} (501 without a voice)."
  (:require ["crypto" :as crypto]
            [claudia.node.http :as io]
            [claudia.node.env :refer [env]]
            ["fs/promises" :as fsp]
            ["path" :as path]
            [clojure.string :as str]))

(def ^:private max-chars 1200)
(def ^:private max-body (* 64 1024))
(def ^:private bytes-per-second (* 24000 2))
(def ^:private reference-text
  "Hello. I am going to walk you through this one part at a time. Take your time, and ask me about anything you see.")

(defonce ^:private cache-dir (atom nil))
;; The service makes one clip at a time: ours wait in line.
(defonce ^:private line (atom (js/Promise.resolve nil)))
(defonce ^:private pending (atom {}))

(defn start! [data-dir] (reset! cache-dir (path/join data-dir "narration")))

(defn- config
  "A promise of {:url :token} or nil."
  []
  (let [url (env "HUB_TTS_URL")
        file (env "HUB_TTS_TOKEN_FILE")]
    (-> (if file
          (-> (fsp/readFile file "utf8") (.then str/trim) (.catch (fn [_] nil)))
          (js/Promise.resolve (env "HUB_TTS_TOKEN")))
        (.then (fn [token]
                 (when (and url (not (str/blank? token)))
                   {:url (str/replace url #"/$" "") :token token}))))))

(defn- sha [s] (-> (crypto/createHash "sha256") (.update s) (.digest "hex")))

(defn- wav
  "Raw 16-bit mono PCM at 24 kHz as a WAV file: the 44-byte RIFF header, then the samples."
  [^js pcm]
  (let [out (js/Buffer.alloc (+ 44 (.-length pcm)))]
    (.write out "RIFF" 0 "ascii") (.writeUInt32LE out (+ 36 (.-length pcm)) 4)
    (.write out "WAVE" 8 "ascii") (.write out "fmt " 12 "ascii")
    (.writeUInt32LE out 16 16) (.writeUInt16LE out 1 20) (.writeUInt16LE out 1 22)
    (.writeUInt32LE out 24000 24) (.writeUInt32LE out 48000 28) (.writeUInt16LE out 2 32) (.writeUInt16LE out 16 34)
    (.write out "data" 36 "ascii") (.writeUInt32LE out (.-length pcm) 40)
    (.copy pcm out 44)
    out))

(defn- speak [{:keys [url token]} ^js form]
  (-> (js/fetch (str url "/v1/audio/speech")
                #js {:method "POST" :headers #js {:authorization (str "Bearer " token)} :body form
                     :signal (js/AbortSignal.timeout 120000)})
      (.then (fn [^js r]
               (when-not (.-ok r) (throw (js/Error. (str "the voice answered " (.-status r)))))
               (.arrayBuffer r)))
      (.then (fn [b]
               (let [pcm (js/Buffer.from b)]
                 (when (< (.-length pcm) 2) (throw (js/Error. "the voice answered with no sound")))
                 pcm)))))

(defn- reference
  "The narrator's reference clip for a described voice, made once."
  [c voice]
  (let [file (path/join @cache-dir (str "narrator-" (subs (sha (str "ref1\n" voice "\n" reference-text)) 0 24) ".wav"))]
    (-> (fsp/readFile file)
        (.catch (fn [_]
                  (let [form (js/FormData.)]
                    (.set form "text" reference-text)
                    (.set form "instruction" voice)
                    (-> (speak c form)
                        (.then (fn [pcm]
                                 (let [w (wav pcm)]
                                   (-> (fsp/mkdir @cache-dir #js {:recursive true})
                                       (.then #(fsp/writeFile file w))
                                       (.then (fn [_] w))))))))))
        (.then (fn [w] {:wav w :text reference-text})))))

(defn- narrate
  "A promise of the PCM for text in the default voice, or a clone of the described one."
  [c text instruction]
  (let [voice (subs (str/trim (or instruction "")) 0 (min 200 (count (str/trim (or instruction "")))))
        who (if (seq voice) (str "made:" voice) (str "default:" (:url c)))
        key (sha (str "clone1\n" who "\n" text))
        file (path/join @cache-dir (str key ".pcm"))]
    (or (get @pending key)
        (let [p (-> (fsp/readFile file)
                    (.catch (fn [_]
                              (let [next (.then @line
                                                (fn [_]
                                                  (-> (if (seq voice) (reference c voice) (js/Promise.resolve nil))
                                                      (.then (fn [ref]
                                                               (let [form (js/FormData.)]
                                                                 (.set form "text" text)
                                                                 (when ref
                                                                   (.set form "ref_audio" (js/Blob. #js [(:wav ref)] #js {:type "audio/wav"}) "narrator.wav")
                                                                   (.set form "ref_text" (:text ref)))
                                                                 (speak c form))))
                                                      (.then (fn [pcm]
                                                               (-> (fsp/mkdir @cache-dir #js {:recursive true})
                                                                   (.then #(fsp/writeFile file pcm))
                                                                   (.then (fn [_] pcm))))))))]
                                (reset! line (.catch next (fn [_] nil)))
                                next)))
                    (.finally #(swap! pending dissoc key)))]
          (swap! pending assoc key p)
          p))))

(defn call
  "GET or POST /hub/narrate for a signed-in caller."
  [^js req ^js res]
  (case (.-method req)
    "GET" (.then (config) #(io/send-json res 200 {:voice (boolean %)}))
    "POST" (-> (js/Promise.all #js [(config) (io/read-json req max-body)])
               (.then (fn [[c body]]
                        (let [text (when (string? (:text body)) (str/trim (:text body)))
                              text (when text (subs text 0 (min max-chars (count text))))
                              instruction (when (string? (:instruction body)) (:instruction body))]
                          (cond
                            (str/blank? text) (io/send-json res 400 {:error "text to say"})
                            (not c) (io/send-json res 501 {:error "no voice set up on this hub (HUB_TTS_URL, HUB_TTS_TOKEN_FILE)"})
                            :else (-> (narrate c text instruction)
                                      (.then (fn [^js pcm]
                                               (.writeHead res 200 #js {"content-type" "audio/L16;rate=24000;channels=1"
                                                                        "x-seconds" (.toFixed (/ (.-length pcm) bytes-per-second) 3)
                                                                        "cache-control" "private, max-age=86400"})
                                               (.end res pcm)))
                                      (.catch (fn [e] (io/send-json res 502 {:error (.-message e)}))))))))
               (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 400 {:error (.-message e)})))))
    (do (.setHeader res "allow" "GET, POST")
        (io/send-json res 405 {:error "GET or POST"}))))
