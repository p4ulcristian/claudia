(ns claudia.web.speech
  "Talking mode's voice (README decision 21): one reply at a time, read aloud by the hub's voice. A
  new reply cuts off the one before; stop! (talking mode off, another chat, the mic held, the page
  hidden) cuts it at once and drops what is still on its way, so nothing is said late."
  (:require [claudia.web.voice :as voice]))

(defonce ^:private state (atom {:ctx nil :source nil :n 0}))

(defn- visible? [] (= "visible" (.-visibilityState js/document)))

(defn- context! []
  (or (:ctx @state)
      (:ctx (swap! state assoc :ctx (js/AudioContext.)))))

(defn unlock!
  "Readies the sound inside a tap (iOS plays only after one)."
  []
  (let [^js c (context!)]
    (when (= "suspended" (.-state c)) (.catch (.resume c) (fn [_] nil)))))

(defn stop!
  "Silence now; whatever is on its way is dropped."
  []
  (let [{:keys [^js source]} @state]
    (when source (try (.stop source) (catch :default _ nil)))
    (swap! state #(-> % (assoc :source nil) (update :n inc)))))

(defn- play! [^js buf]
  (let [^js c (context!)
        ^js src (.createBufferSource c)]
    (set! (.-buffer src) (voice/audio-buffer c buf))
    (.connect src (.-destination c))
    (.start src)
    (swap! state assoc :source src)))

(defn say!
  "Reads text aloud (cutting off what was being said), when the page is in front."
  [text]
  (when (and (seq text) (visible?))
    (stop!)
    (let [n (:n @state)]
      (-> (voice/fetch-pcm text nil)
          (.then #(when (and (= n (:n @state)) (visible?)) (play! %)))
          (.catch #(js/console.warn "talking mode:" (.-message %)))))))

(defonce ^:private hidden-stops
  ;; A page going to the background goes quiet.
  (delay (.addEventListener js/document "visibilitychange" #(when-not (visible?) (stop!)))))

(defn start! "Installs the background stop once." [] @hidden-stops)
