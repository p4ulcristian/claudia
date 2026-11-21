(ns claudia.slave.voice-messages
  "Voice messages (README decision 90): a chat with a voice that ends a turn by itself or asks leaves
  a message; every page of its person gets the whole list (`voices`) and plays one only on a tap; a
  page says when one was heard to its end or swiped away (`heard`), and it goes on every device. A
  message waits at most a day; a chat whose voice goes Off, or that is archived, drops its own."
  (:require [claudia.slave.store :as store]))

(def keep-ms "How long a message waits to be heard: a day." (* 24 60 60 1000))

(defn message
  "A chat's `speak` (chats/speak!: {:chat :id :voice :text or :ask}) as a message kept at now."
  [{:keys [chat id voice text ask]} now]
  (cond-> {:chat chat :id (str id) :voice voice :at now}
    (seq text) (assoc :text text)
    ask (assoc :ask ask)))

(defn frame
  "The `voices` frame: the messages still in time at now, oldest first."
  [msgs now]
  {:type "voices" :messages (filterv #(> (:at %) (- now keep-ms)) msgs)})

(defn current-frame
  "The `voices` frame now; messages past their day are dropped from the store first."
  []
  (let [now (js/Date.now)]
    (store/drop-voice-messages-before! (- now keep-ms))
    (frame (store/voice-messages) now)))

(defn keep! [speak] (store/add-voice-message! (message speak (js/Date.now))))

(defn heard!
  "Chat's messages with these ids were heard or swiped away."
  [chat ids]
  (doseq [id ids :when (string? id)] (store/drop-voice-message! chat id)))

(defn drop-chat! [chat] (store/drop-chat-voice-messages! chat))
