(ns claudia.web.world.signs
  "A name sign over every building: the project its chats work on (iron-rainbow, claudia…), facing
  the camera, lit while one of its chats works (claudia.web.world.atlas)."
  (:require [claudia.web.world.atlas :as atlas]))

(def ^:private spec {:px-w 512 :px-h 96 :cols 2 :rows 32 :width 4.6})

(defn make [scene] (atlas/make scene spec))

(def ^:private font "'Atkinson Hyperlegible Next', system-ui, sans-serif")

(defn- fit
  "s cut to fit max-w at the context's font, with an ellipsis."
  [^js ctx s max-w]
  (loop [t s]
    (if (or (<= (.-width (.measureText ctx t)) max-w) (<= (count t) 1)) t (recur (str (subs t 0 (- (count t) 2)) "…")))))

(defn- draw! [^js ctx x y w h {:keys [label working?]}]
  (.beginPath ctx)
  (.roundRect ctx (+ x 4) (+ y 4) (- w 8) (- h 8) 40)
  (set! (.-fillStyle ctx) (if working? "rgb(255,236,190)" "rgb(250,247,238)"))
  (.fill ctx)
  (set! (.-lineWidth ctx) 5)
  (set! (.-strokeStyle ctx) (if working? "#ffb84d" "#3a3f4b"))
  (.stroke ctx)
  (set! (.-font ctx) (str "700 54px " font))
  (set! (.-fillStyle ctx) "#15171d")
  (set! (.-textAlign ctx) "center")
  (set! (.-textBaseline ctx) "middle")
  (.fillText ctx (fit ctx label (- w 50)) (+ x (/ w 2)) (+ y (/ h 2) 4))
  (set! (.-textAlign ctx) "start"))

(defn sync!
  "Signs for buildings (the plan's, in order); working: ids of buildings where a chat works."
  [rt buildings working]
  (atlas/sync! rt (map #(assoc % :working? (contains? working (:id %))) buildings)
               (juxt :label :working?) draw!))

(defn- spot [{:keys [pos size]}]
  (let [[x y z] pos] [x (+ y (* 1.2 (first size) 1.55) 0.7) z]))

(defn frame! [rt ^js camera]
  (atlas/frame! rt #(atlas/facing (spot %) (.-position camera) -0.2) nil nil))
