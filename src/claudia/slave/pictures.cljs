(ns claudia.slave.pictures
  "Picture history: the picture each turn left behind, so the page can step back through them. The
  newest is the live picture (the chat's scene); older ones are read only."
  (:require [claudia.slave.store :as store]))

(def imported
  "The turn name of a picture older than the history: a chat brought over (or drawn before the
  history was kept) has only its last picture."
  "imported")

(defn listing
  "The chat's pictures, oldest first: [{:turn :title :at}]."
  [id]
  (let [ps (store/pictures id)
        s (when (empty? ps) (store/scene id))]
    (cond (seq ps) ps
          (seq (:blocks s)) [{:turn imported :title (or (:title s) "") :at (or (:at (store/chat id)) 0)}]
          :else [])))

(defn record!
  "Keeps the picture turn left (prev = the picture before this draw)."
  [id turn prev scene]
  (let [now (js/Date.now)]
    (when (and (empty? (store/pictures id)) (seq (:blocks prev)) (not= turn imported))
      (store/save-picture! id imported prev (min (or (:at (store/chat id)) 0) (dec now))))
    (store/save-picture! id turn scene now)))

(defn one
  "One picture's scene by its turn, or nil."
  [id turn]
  (or (store/picture id turn)
      (when (= turn imported) (store/scene id))))
