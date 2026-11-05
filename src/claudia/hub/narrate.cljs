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
  (:require [claudia.node.audio :as audio]
            [claudia.node.crypto :refer [sha256-hex]]
            [claudia.node.http :as io]
            [claudia.node.env :refer [env]]
            [claudia.shared.text :as text]
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

(defn- form-of
  "Form fields for the speech route, as [name value] or [name value file-name]."
  [fields]
  (let [form (js/FormData.)]
    (doseq [[k v file-name] fields]
      (if file-name (.set form k v file-name) (.set form k v)))
    form))

(defn- saved
  "Writes data to file in the cache folder -> Promise of data."
  [file data]
  (-> (fsp/mkdir @cache-dir #js {:recursive true})
      (.then #(fsp/writeFile file data))
      (.then (fn [_] data))))

(defn- reference
  "The narrator's reference clip for a described voice, made once."
  [c voice]
  (let [file (path/join @cache-dir (str "narrator-" (subs (sha256-hex (str "ref1\n" voice "\n" reference-text)) 0 24) ".wav"))]
    (-> (fsp/readFile file)
        (.catch (fn [_]
                  (-> (speak c (form-of [["text" reference-text] ["instruction" voice]]))
                      (.then #(saved file (audio/wav % 24000))))))
        (.then (fn [w] {:wav w :text reference-text})))))

(defn voice-of
  "A story's voice description, trimmed and cut to 200 characters (\"\" for none)."
  [instruction]
  (text/cut (str/trim (or instruction "")) 200 ""))

(defn clip-key
  "What a clip is kept under: its voice (described, or the service's default) and its text."
  [voice url said]
  (sha256-hex (str "clone1\n" (if (seq voice) (str "made:" voice) (str "default:" url)) "\n" said)))

(defn- clone-form [said ref]
  (form-of (cond-> [["text" said]]
             ref (conj ["ref_audio" (js/Blob. #js [(:wav ref)] #js {:type "audio/wav"}) "narrator.wav"]
                       ["ref_text" (:text ref)]))))

(defn- made
  "Makes the clip in line after the ones before it -> Promise of the PCM, written to file."
  [c said voice file]
  (let [p (.then @line
                 (fn [_]
                   (-> (if (seq voice) (reference c voice) (js/Promise.resolve nil))
                       (.then #(speak c (clone-form said %)))
                       (.then #(saved file %)))))]
    (reset! line (.catch p (fn [_] nil)))
    p))

(defn- narrate
  "A promise of the PCM for text in the default voice, or a clone of the described one."
  [c said instruction]
  (let [voice (voice-of instruction)
        k (clip-key voice (:url c) said)
        file (path/join @cache-dir (str k ".pcm"))]
    (or (get @pending k)
        (let [p (-> (fsp/readFile file)
                    (.catch (fn [_] (made c said voice file)))
                    (.finally #(swap! pending dissoc k)))]
          (swap! pending assoc k p)
          p))))

(defn said-text
  "The text to say from a POST body: trimmed and cut to max-chars, nil when there is none."
  [body]
  (some-> (when (string? (:text body)) (str/trim (:text body))) (text/cut max-chars "")))

(defn- answer-pcm [^js res ^js pcm]
  (.writeHead res 200 #js {"content-type" "audio/L16;rate=24000;channels=1"
                           "x-seconds" (.toFixed (/ (.-length pcm) bytes-per-second) 3)
                           "cache-control" "private, max-age=86400"})
  (.end res pcm))

(defn- say [res c body]
  (let [said (said-text body)
        instruction (when (string? (:instruction body)) (:instruction body))]
    (cond
      (str/blank? said) (io/send-json res 400 {:error "text to say"})
      (not c) (io/send-json res 501 {:error "no voice set up on this hub (HUB_TTS_URL, HUB_TTS_TOKEN_FILE)"})
      :else (-> (narrate c said instruction)
                (.then #(answer-pcm res %))
                (.catch (fn [e] (io/send-json res 502 {:error (.-message e)})))))))

(defn call
  "GET or POST /hub/narrate for a signed-in caller."
  [^js req ^js res]
  (case (.-method req)
    "GET" (.then (config) #(io/send-json res 200 {:voice (boolean %)}))
    "POST" (-> (js/Promise.all #js [(config) (io/read-json req max-body)])
               (.then (fn [[c body]] (say res c body)))
               (.catch (fn [e] (when-not (.-headersSent res) (io/send-json res 400 {:error (.-message e)})))))
    (do (.setHeader res "allow" "GET, POST")
        (io/send-json res 405 {:error "GET or POST"}))))

(defn voice?
  "Promise of whether a voice service is set up."
  []
  (.then (config) boolean))

(defn clips
  "Promise of the lines ([{:text :instruction}]) said: [{:text :instruction :pcm Buffer}], one
  after the other (kept clips come from disk). Rejects when there is no voice or it fails."
  [lines]
  (.then (config)
         (fn [c]
           (when-not c (throw (js/Error. "no voice set up on this hub")))
           (reduce (fn [chain {:keys [text instruction] :as said}]
                     (.then chain (fn [out] (.then (narrate c text instruction) #(conj out (assoc said :pcm %))))))
                   (js/Promise.resolve [])
                   lines))))
