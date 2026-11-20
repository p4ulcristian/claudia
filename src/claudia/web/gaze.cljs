(ns claudia.web.gaze
  "The head pointer (2026-11-20; eyes first, but they jump and drift): in the iPhone/iPad app on a
  Face ID device, a chalk ring shows where your head points, with a short fading trail, and the
  picture's block under it glows. The app
  (GazeTracker.swift) reads the face with ARKit and calls window.claudiaGaze(reading) about 30
  times a second; the page learns the map from five dots you point at (bad ones asked again, one
  model per screen turn, kept in localStorage), smooths it, takes every tap as pointing at that
  spot (drift fades) and draws (claudia.web.gaze.math does the arithmetic). Switched in Settings or with Alt+G; the
  layer is plain DOM over the page, outside React, moved every frame; taps pass through it."
  (:require [reagent.core :as r]
            [claudia.web.gaze.math :as m]))

(def ^:private handler-name "claudiaGaze")
(def ^:private models-key
  "Where the models live; a new name when calibration changes, so old models are not reused."
  "headModels")
(def ^:private on-key "gazeOn")
(def ^:private trail-n 4)
(def ^:private trail-ms 90)

(defn- handler []
  (some-> js/window (unchecked-get "webkit") (unchecked-get "messageHandlers") (unchecked-get handler-name)))

(defn here? "Whether the page runs in the app with a gaze tracker." [] (some? (handler)))

(defn- ask [op] (.postMessage ^js (handler) #js {:op op}))

;; ---- state ----

;; What the settings section shows: {:on? :phase :error :quality}.
(defonce status (r/atom {:on? false}))
;; The arithmetic's state (claudia.web.gaze.math/step), the screen turn it is for, the trail.
(defonce ^:private gaze (atom nil))
(defonce ^:private turn (atom nil))
(defonce ^:private trail-state (atom {:points [] :at 0}))
(defonce ^:private els (atom nil))
(defonce ^:private glowing (atom nil))
;; Set by Calibrate again: the next screen turn seen calibrates even when it has a model.
(defonce ^:private fresh? (atom false))

(defn- stored [k] (try (js/localStorage.getItem k) (catch :default _ nil)))
(defn- store! [k v] (try (js/localStorage.setItem k v) (catch :default _ nil)))

(defn- models []
  (or (some-> (stored models-key) js/JSON.parse (js->clj :keywordize-keys true)) {}))

(defn- save-model! [t model]
  (store! models-key (js/JSON.stringify (clj->js (assoc (models) (keyword (str t)) model)))))

(defn- model-for [t] (get (models) (keyword (str t))))

;; ---- the layer ----

(defn- el [cls] (doto (js/document.createElement "div") (.setAttribute "class" cls)))

(defn- layer!
  "The layer's elements, made once: the ring, the trail, the calibration dot and its hint."
  []
  (or @els
      (let [root (el "gaze-layer")
            parts {:root root :ring (el "gaze-ring") :dot (el "gaze-dot") :hint (el "gaze-hint")
                   :trail (vec (repeatedly trail-n #(el "gaze-trail")))}]
        (doseq [e (concat [(:ring parts) (:dot parts) (:hint parts)] (:trail parts))] (.appendChild root e))
        (js/document.body.appendChild root)
        (reset! els parts))))

(defn- remove-layer! []
  (some-> @els :root .remove)
  (some-> ^js @glowing .-classList (.remove "gazed"))
  (reset! els nil)
  (reset! glowing nil))

(defn- place! [^js e [x y] opacity]
  (set! (.. e -style -transform) (str "translate(" (* x js/innerWidth) "px," (* y js/innerHeight) "px)"))
  (set! (.. e -style -opacity) (str opacity)))

(defn- glow!
  "The picture's block at [x y] (fractions) glows; the one before stops."
  [[x y]]
  (let [b (some-> (js/document.elementFromPoint (* x js/innerWidth) (* y js/innerHeight)) (.closest "section.block"))]
    (when-not (identical? b @glowing)
      (some-> ^js @glowing .-classList (.remove "gazed"))
      (some-> ^js b .-classList (.add "gazed"))
      (reset! glowing b))))

(defn- trail-points
  "The trail after the pointer is at p at time t: a point added every trail-ms, the newest last."
  [{:keys [points at] :as tr} p t]
  (if (< (- t at) trail-ms) tr {:points (vec (take-last trail-n (conj points p))) :at t}))

(defn- draw-pointer! [{:keys [ring dot hint] trail-els :trail} {:keys [at lost?]} t]
  (let [tr (swap! trail-state trail-points at t)]
    (place! ring at (if lost? 0.25 1))
    (doseq [[i e] (map-indexed vector trail-els)]
      (if-let [p (get (:points tr) i)] (place! e p (* 0.12 (inc i))) (place! e [0 0] 0)))
    (place! dot [0 0] 0)
    (set! (.. hint -style -opacity) "0")
    (when-not lost? (glow! at))))

(def ^:private hints
  {false "Point your nose at the dot."
   true "A few dots again."})

(defn- draw-dot! [{:keys [ring dot hint] trail-els :trail} {:keys [at p again?]}]
  (set! (.-textContent hint) (hints (boolean again?)))
  (place! ring [0 0] 0)
  (doseq [e trail-els] (place! e [0 0] 0))
  (place! dot at 1)
  (set! (.. dot -style -scale) (str (- 1.4 p)))
  (set! (.. hint -style -opacity) "1"))

(defn- draw! [show t]
  (let [parts (layer!)]
    (case (:kind show)
      :pointer (draw-pointer! parts show t)
      :dot (draw-dot! parts show)
      nil)))

;; ---- readings ----

(defn- phase-news!
  "After a step from state before (prev: the state of the last reading): a new model is kept, and
  the status follows the phase."
  [prev before after]
  (when (and (= :calibrating (:phase before)) (= :tracking (:phase after)))
    (save-model! @turn (:model after)))
  (when (not= (:phase prev) (:phase after))
    (swap! status assoc :phase (:phase after) :quality (:error after))))

(defn- start-for-turn
  "The state for screen turn t: its saved model, else a new calibration at now."
  [t now]
  (let [model (when-not @fresh? (model-for t))]
    (reset! fresh? false)
    (if model (m/tracking model) (m/calibrating now))))

(defn- reading! [^js r]
  (when (:on? @status)
    (let [now (js/performance.now)
          rd (js->clj r :keywordize-keys true)
          prev @gaze
          before (if (= (:turn rd) @turn) @gaze (do (reset! turn (:turn rd)) (start-for-turn (:turn rd) now)))
          after (m/step before rd now)]
      (reset! gaze after)
      (phase-news! prev before after)
      (draw! (:show after) now))))

;; ---- on and off ----

(defn stop! []
  (when (here?) (.catch (ask "stop") (fn [_] nil)))
  (store! on-key "0")
  (reset! gaze nil)
  (reset! turn nil)
  (remove-layer!)
  (reset! status {:on? false}))

(defn start!
  "Turns the tracker on; calibrates when this screen turn has no model yet."
  []
  (when (here?)
    (store! on-key "1")
    (reset! status {:on? true :phase :starting})
    (-> (ask "start")
        (.catch (fn [e] (stop!) (swap! status assoc :error (or (.-message e) (str e))))))))

(defn recalibrate!
  "Calibrates again (the new model replaces this screen turn's old one)."
  []
  (reset! fresh? true)
  (reset! turn nil)
  (reset! gaze nil)
  (if (:on? @status) (swap! status assoc :phase :calibrating) (start!)))

(defn toggle! [] (if (:on? @status) (stop!) (start!)))

(defn- on-key-down [^js e]
  (cond
    (and (.-altKey e) (= "KeyG" (.-code e))) (do (.preventDefault e) (toggle!))
    (and (= "Escape" (.-key e)) (= :calibrating (:phase @status))) (do (.preventDefault e) (stop!))))

(defn- on-tap
  "A tap while tracking: you point where you tap, so it corrects the pointer (claudia.web.gaze.math/tapped)."
  [^js e]
  (when (= :tracking (:phase @gaze))
    (swap! gaze m/tapped [(/ (.-clientX e) js/innerWidth) (/ (.-clientY e) js/innerHeight)])))

(defn install!
  "Takes the app's readings and Alt+G; switches back on when it was on at the last visit."
  []
  (when (here?)
    (set! (.-claudiaGaze js/window) reading!)
    (js/window.addEventListener "keydown" on-key-down true)
    (js/window.addEventListener "pointerdown" on-tap #js {:capture true :passive true})
    (when (= "1" (stored on-key)) (start!))))

;; ---- settings ----

(defn- status-line [{:keys [on? phase quality error]}]
  (cond error (str "Could not start: " error)
        (not on?) "Off."
        (= phase :calibrating) "Setting up: point your nose at each dot."
        (= phase :failed) "Setup saw too little of your face. Try again facing the iPad."
        (= phase :tracking) (str "On. Tap anywhere to re-centre." (when quality (str " Setup error: about "
                                                         (js/Math.round (* 100 quality)) "% of the screen.")))
        :else "Starting…"))

(defn settings-section
  "Settings' head pointer section: only in the app on a device that can track the face."
  []
  (when (here?)
    (let [s @status]
      [:section.st-section.st-gaze
       [:h3 "Head pointer " [:span.muted.small "Face ID camera · Alt+G"]]
       [:p.small (status-line s)]
       [:div.st-gaze-buttons
        [:button {:type "button" :on-click toggle!} (if (:on? s) "Turn off" "Turn on")]
        [:button {:type "button" :on-click recalibrate!} "Set up again"]]])))
