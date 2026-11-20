(ns claudia.web.here
  "Which of the person's devices reads replies aloud (README decision 21): the one touched last.
  A touch, click or key here tells every server (`here`, at most every resend-ms); a socket that
  opens says how long ago this page was touched, so a runner that restarted still knows. A touch
  also readies the sound (iOS plays only after one)."
  (:require [claudia.web.speech :as speech]
            [claudia.web.ws :as ws]))

(defonce ^:private touched (atom 0))
(defonce ^:private sent (atom 0))
(defonce ^:private started (atom false))

(def ^:private resend-ms "A touch is told again at most this often." 5000)
(def ^:private max-ago-ms "A page touched longer ago than this does not say so." (* 24 60 60 1000))

(defn frame-at
  "The `here` frame for a page touched at touched-ms, at now; nil when never or too long ago."
  [touched-ms now]
  (let [ago (- now touched-ms)]
    (when (and (pos? touched-ms) (<= 0 ago max-ago-ms))
      {:type "here" :ago ago})))

(defn frame "This page's `here` frame now, or nil." [] (frame-at @touched (js/Date.now)))

(defn- touched! []
  (let [now (js/Date.now)]
    (reset! touched now)
    (speech/unlock!)
    (when (>= (- now @sent) resend-ms)
      (reset! sent now)
      (ws/send-live! {:type "here" :ago 0}))))

(defn start!
  "Once per page: touches, clicks and keys count."
  []
  (when-not @started
    (reset! started true)
    (doseq [e ["pointerdown" "keydown" "touchstart"]]
      (js/window.addEventListener e touched! #js {:passive true :capture true}))))
