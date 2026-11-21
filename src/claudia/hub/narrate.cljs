(ns claudia.hub.narrate
  "Narration for 3D stories (the scene3d form's narrator): one voice for every server. A line of text
  becomes speech through a voice service, kept on disk by its text (HUB_DATA/narration), so a story
  replays without asking again. The service: HUB_TTS_URL, its token in HUB_TTS_TOKEN_FILE (or
  HUB_TTS_TOKEN). Its speech route <url>/v1/audio/speech takes form fields: text alone (the
  service's default voice), or text with ref_audio and ref_text (a cloned voice), each with an
  optional language (an ISO code: the default voice then speaks from its clip in that language);
  it answers raw 16-bit mono PCM at 24 kHz.

  Asked for a voice only by a description (a story's `voice`), the service invents a slightly
  different person for every line, so a description makes ONE reference clip (kept on disk) and
  every line is spoken in a clone of it. A clone keeps the reference's accent, so the reference is
  spoken in the story's language (`language`; English without one), from a sentence in that
  language. For a language with no reference sentence here, the lines are spoken by the service's
  default voice in that language instead (a native accent beats the described voice).

  The service sends a clip a sentence at a time, and a clip being made goes on to the page the same
  way, so its first sentence is heard while the rest is made; it is kept on disk only once whole.

  GET /hub/narrate -> {voice: bool}; POST /hub/narrate {text, instruction?, language?} -> the PCM
  (audio/L16;rate=24000;channels=1; x-seconds only for a clip already whole, kept or made for
  another caller; a clip being made comes chunked, without a length), or {error} (501 without a
  voice; a clip failing after its first sound breaks the connection off)."
  (:require [claudia.node.audio :as audio]
            [claudia.node.crypto :refer [sha256-hex]]
            [claudia.node.http :as io]
            [claudia.node.env :as env]
            [claudia.shared.narration :as narration]
            [claudia.shared.text :as text]
            ["fs/promises" :as fsp]
            ["path" :as path]
            [clojure.string :as str]))

(def ^:private max-chars 1200)
(def ^:private max-body (* 64 1024))
(def ^:private bytes-per-second (* 24000 2))
(def ^:private reference-texts
  "What a described voice's reference clip says, by language (ISO 639 code)."
  {"en" "Hello. I am going to walk you through this one part at a time. Take your time, and ask me about anything you see."
   "hu" "Szia. Most végigvezetlek ezen, lépésről lépésre. Nyugodtan nézz körül, és kérdezz bármiről, amit látsz."
   "de" "Hallo. Ich führe dich jetzt Schritt für Schritt hindurch. Lass dir Zeit, und frag mich nach allem, was du siehst."
   "fr" "Bonjour. Je vais vous guider pas à pas. Prenez votre temps, et posez-moi des questions sur tout ce que vous voyez."
   "es" "Hola. Te voy a guiar paso a paso. Tómate tu tiempo y pregúntame por cualquier cosa que veas."
   "it" "Ciao. Ti guiderò passo dopo passo. Prenditi il tuo tempo e chiedimi qualunque cosa tu veda."})

(defn reference-text
  "The sentence a described voice's reference clip says in language (nil: English), or nil when
  there is none in that language."
  [language]
  (get reference-texts (or language "en")))

(defn spoken-voice
  "The described voice the lines are cloned from: voice, or \"\" (the service's default voice)
  when language has no reference sentence, so no clip in another language lends its accent."
  [voice language]
  (if (reference-text language) voice ""))

(defonce ^:private cache-dir (atom nil))
;; The service makes one clip at a time: ours wait in line.
(defonce ^:private line (atom (js/Promise.resolve nil)))
(defonce ^:private pending (atom {}))

(defn start! [data-dir] (reset! cache-dir (path/join data-dir "narration")))

(defn- config "A promise of {:url :token} or nil." [] (env/service "HUB_TTS"))

(defn read-all
  "Reads a web ReadableStream reader to its end, handing each chunk (a Buffer) to sink as it comes
  -> Promise of all of them joined. Rejects when the stream breaks off, so a cut answer is never
  taken for a whole one."
  [^js reader sink]
  (letfn [(step [chunks]
            (.then (.read reader)
                   (fn [^js r]
                     (if (.-done r)
                       (js/Buffer.concat (into-array chunks))
                       (let [^js v (.-value r)
                             b (js/Buffer.from (.-buffer v) (.-byteOffset v) (.-byteLength v))]
                         (sink b)
                         (step (conj chunks b)))))))]
    (step [])))

(defn whole-pcm
  "The PCM a finished answer brought, or an error when it brought no sound."
  [^js pcm]
  (when (< (.-length pcm) 2) (throw (js/Error. "the voice answered with no sound")))
  pcm)

(defn- speak
  "Asks the voice for form's speech -> Promise of its PCM, once the answer is complete. The service
  sends it a sentence at a time: sink (nil: none) gets each chunk as it arrives."
  ([c form] (speak c form nil))
  ([{:keys [url token]} ^js form sink]
   (-> (js/fetch (str url "/v1/audio/speech")
                 #js {:method "POST" :headers #js {:authorization (str "Bearer " token)} :body form
                      :signal (js/AbortSignal.timeout 120000)})
       (.then (fn [^js r]
                (when-not (.-ok r) (throw (js/Error. (str "the voice answered " (.-status r)))))
                (read-all (.getReader (.-body r)) (or sink (fn [_])))))
       (.then whole-pcm))))

(defn- form-of
  "Form fields for the speech route, as [name value] or [name value file-name], with the language
  when there is one."
  [fields language]
  (let [form (js/FormData.)]
    (doseq [[k v file-name] (cond-> fields language (conj ["language" language]))]
      (if file-name (.set form k v file-name) (.set form k v)))
    form))

(defn- saved
  "Writes data to file in the cache folder -> Promise of data."
  [file data]
  (-> (fsp/mkdir @cache-dir #js {:recursive true})
      (.then #(fsp/writeFile file data))
      (.then (fn [_] data))))

(defn reference-key
  "What a described voice's reference clip is kept under: the voice, its words and their language
  (nil: none asked, as before languages)."
  [voice said language]
  (subs (sha256-hex (str "ref1\n" voice "\n" said (when language (str "\nlanguage:" language)))) 0 24))

(defn- reference
  "The narrator's reference clip for a described voice in language, made once."
  [c voice language]
  (let [said (reference-text language)
        file (path/join @cache-dir (str "narrator-" (reference-key voice said language) ".wav"))]
    (-> (fsp/readFile file)
        (.catch (fn [_]
                  (-> (speak c (form-of [["text" said] ["instruction" voice]] language))
                      (.then #(saved file (audio/wav % 24000))))))
        (.then (fn [w] {:wav w :text said})))))

(defn voice-of
  "A story's voice description, trimmed and cut to 200 characters (\"\" for none)."
  [instruction]
  (text/cut (str/trim (or instruction "")) 200 ""))

(defn clip-key
  "What a clip is kept under: its voice (described, or the service's default), its language (nil:
  none asked, as before languages) and its text."
  [voice language url said]
  (sha256-hex (str "clone1\n" (if (seq voice) (str "made:" voice) (str "default:" url))
                   (when language (str "\nlanguage:" language)) "\n" said)))

(defn- clone-form [said ref language]
  (form-of (cond-> [["text" said]]
             ref (conj ["ref_audio" (js/Blob. #js [(:wav ref)] #js {:type "audio/wav"}) "narrator.wav"]
                       ["ref_text" (:text ref)]))
           language))

(defn- made
  "Makes the clip in line after the ones before it, handing sink (nil: none) its chunks as they
  come -> Promise of the PCM. Only a complete answer is written to file: a failed or cut one
  rejects before the write, so no part of a clip is ever kept."
  [c said voice language file sink]
  (let [p (.then @line
                 (fn [_]
                   (-> (if (seq voice) (reference c voice language) (js/Promise.resolve nil))
                       (.then #(speak c (clone-form said % language) sink))
                       (.then #(saved file %)))))]
    (reset! line (.catch p (fn [_] nil)))
    p))

(defn clip
  "A promise of the PCM for text in language (nil: none asked), in the default voice or a clone of
  the described one: from disk when kept, else made (sink, nil for none, gets its chunks as they
  come). A caller asking while the same clip is being made waits for it whole, and sink gets
  nothing."
  [c said instruction language sink]
  (let [voice (spoken-voice (voice-of instruction) language)
        k (clip-key voice language (:url c) said)
        file (path/join @cache-dir (str k ".pcm"))]
    (or (get @pending k)
        (let [p (-> (fsp/readFile file)
                    (.catch (fn [_] (made c said voice language file sink)))
                    (.finally #(swap! pending dissoc k)))]
          (swap! pending assoc k p)
          p))))

(defn said-text
  "The text to say from a POST body: trimmed and cut to max-chars, nil when there is none."
  [body]
  (some-> (when (string? (:text body)) (str/trim (:text body))) (text/cut max-chars "")))

(def ^:private pcm-type "audio/L16;rate=24000;channels=1")

(defn- answer-pcm
  "A whole clip (kept on disk, or made for another caller first): its length is known."
  [^js res ^js pcm]
  (.writeHead res 200 #js {"content-type" pcm-type
                           "x-seconds" (.toFixed (/ (.-length pcm) bytes-per-second) 3)
                           "cache-control" "private, max-age=86400"})
  (.end res pcm))

(defn- piped
  "A sink sending each chunk of a clip being made on to res as it comes (chunked, no length yet)."
  [^js res]
  (fn [^js part]
    (when-not (.-headersSent res)
      (.writeHead res 200 #js {"content-type" pcm-type "cache-control" "private, max-age=86400"}))
    (when-not (.-destroyed res) (.write res part))))

(defn- answered
  "The clip is complete: a streamed answer ends, a whole one is sent."
  [^js res ^js pcm]
  (if (.-headersSent res) (.end res) (answer-pcm res pcm)))

(defn- failed
  "The clip failed: before any sound an error answer; mid-stream the connection breaks, so the page
  sees a cut answer, never a clean end."
  [^js res ^js e]
  (if (.-headersSent res) (.destroy res) (io/send-json res 502 {:error (.-message e)})))

(defn- say [res c body]
  (let [said (said-text body)
        instruction (when (string? (:instruction body)) (:instruction body))]
    (cond
      (str/blank? said) (io/send-json res 400 {:error "text to say"})
      (not c) (io/send-json res 501 {:error "no voice set up on this hub (HUB_TTS_URL, HUB_TTS_TOKEN_FILE)"})
      :else (-> (clip c said instruction (narration/language-of body) (piped res))
                (.then #(answered res %))
                (.catch #(failed res %))))))

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
  "Promise of the lines ([{:text :instruction :language}]) said: [{:text :instruction :language
  :pcm Buffer}], one after the other (kept clips come from disk). Rejects when there is no voice or
  it fails."
  [lines]
  (.then (config)
         (fn [c]
           (when-not c (throw (js/Error. "no voice set up on this hub")))
           (reduce (fn [chain {:keys [text instruction language] :as said}]
                     (.then chain (fn [out] (.then (clip c text instruction language nil) #(conj out (assoc said :pcm %))))))
                   (js/Promise.resolve [])
                   lines))))
