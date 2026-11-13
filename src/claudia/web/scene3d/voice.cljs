(ns claudia.web.scene3d.voice
  "The narrator of a scene3d story: the hub turns each line into speech (POST /hub/narrate: raw 16-bit
  mono PCM at 24 kHz, kept on disk by the text); this plays it on the Web Audio clock, so a clip's
  length is known exactly and pausing pauses the voice with the story. A video made off screen has
  a narrator that only notes what it says and when (given)."
  (:require [claudia.web.scene3d.clock :as clock]
            [claudia.web.voice :as voice]))

(defn available? [] (voice/available?))

(defn- context! "The audio context, made on first use." [{:keys [ctx]}]
  (or @ctx (reset! ctx (js/AudioContext. #js {:sampleRate voice/rate}))))

(defn- clip-of "16-bit PCM bytes as a clip {:buffer AudioBuffer :seconds n}." [narrator ^js buf]
  (let [^js b (voice/audio-buffer (context! narrator) buf)]
    {:buffer b :seconds (/ (.-length b) voice/rate)}))

(defn- fetch-clip "A promise of a line's clip, or nil (the error kept)." [{:keys [error] :as narrator} text instruction]
  (-> (voice/fetch-pcm text instruction)
      (.then #(clip-of narrator %))
      (.catch (fn [e] (reset! error (.-message e)) nil))))

(defn- load! "A line's clip, fetched once per text and instruction." [{:keys [clips] :as narrator} text instruction]
  (let [k [instruction text]]
    (or (get @clips k)
        (let [p (fetch-clip narrator text instruction)] (swap! clips assoc k p) p))))

(defn- stop! [{:keys [source]}]
  (when-let [^js s @source] (try (.stop s) (catch :default _ nil)))
  (reset! source nil))

(defn- play! [{:keys [source] :as narrator} {:keys [buffer]}]
  (stop! narrator)
  (let [^js c (context! narrator)]
    (-> (if (= "suspended" (.-state c)) (.catch (.resume c) (fn [_] nil)) (js/Promise.resolve nil))
        (.then (fn [_]
                 (let [^js src (.createBufferSource c)]
                   (set! (.-buffer src) buffer)
                   (.connect src (.-destination c))
                   (.start src)
                   (reset! source src)))))))

(defn- close! [{:keys [ctx] :as narrator}]
  (stop! narrator)
  (some-> ^js @ctx .close)
  (reset! ctx nil))

(defn make
  "One story's narrator: {:load (fn [text instruction] promise of clip or nil) :play :stop :pause
  :resume :close :error (atom)}. A clip is {:buffer AudioBuffer :seconds n}."
  []
  (let [{:keys [ctx error] :as narrator} {:ctx (atom nil) :clips (atom {}) :source (atom nil) :error (atom "")}]
    {:error error
     :load #(load! narrator %1 %2)
     :play #(play! narrator %)
     :stop #(stop! narrator)
     :pause (fn [] (some-> ^js @ctx .suspend))
     :resume (fn [] (some-> ^js @ctx .resume))
     :close #(close! narrator)}))

(defn given
  "The narrator of a video made off screen: the clips' lengths are given ({[instruction text]
  seconds}) and nothing is heard; each line it says is noted in :said (an atom of [{:text
  :instruction :at}], :at the clock's ms), for the video's sound. A line without a clip has none."
  [seconds]
  (let [said (atom [])]
    {:error (atom "")
     :said said
     :load (fn [text instruction]
             (js/Promise.resolve
              (when-let [secs (get seconds [instruction text])]
                {:text text :instruction instruction :seconds secs})))
     :play (fn [{:keys [text instruction]}] (swap! said conj {:text text :instruction instruction :at (clock/now)}))
     :stop (fn [])
     :pause (fn [])
     :resume (fn [])
     :close (fn [])}))
