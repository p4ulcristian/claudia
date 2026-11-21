(ns claudia.web.views.stretch
  "The chat's rubber-band end on the page (README decision 118): a wheel, trackpad or finger pushing
  past the column's top or bottom moves the messages a damped way with it
  (claudia.web.views.pure.stretch), and they spring back once the push stops. Only where the browser
  has no bounce of its own (not on Apple devices) and not with reduced motion. The column itself
  never scrolls further: its children move by a transform (--stretch, resources/web/css/claudia.css),
  so scrollTop, following the bottom and the older pages stay as they are."
  (:require [reagent.hooks :as h]
            [claudia.web.views.pure.stretch :as pure]))

(def ^:private settle-ms
  "No push for this long (a wheel has no letting go): the messages spring back."
  140)

(def ^:private spring-ms
  "The spring back's length (the .spring transition in claudia.css) and a little more."
  420)

(defn- drawn?
  "Whether this page draws the band: no native bounce, no reduced motion."
  []
  (and (not (pure/native-bounce? js/navigator.platform js/navigator.userAgent (or js/navigator.maxTouchPoints 0)))
       (not (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))))

(defn- numbers [^js el]
  {:top (.-scrollTop el) :height (.-scrollHeight el) :client (.-clientHeight el)})

(defn- scroller? [^js n]
  (contains? #{"auto" "scroll"} (.-overflowY (js/getComputedStyle n))))

(defn- inner-scrolls?
  "Whether a box between the event's target and the column takes the step dy itself."
  [^js el ^js target dy]
  (loop [^js n target]
    (cond (or (nil? n) (identical? n el) (not (instance? js/Element n))) false
          (and (scroller? n) (pure/can-scroll? (numbers n) dy)) true
          :else (recur (.-parentElement n)))))

(defn- show! [^js el pull]
  (doto (.-classList el) (.add "stretch") (.remove "spring"))
  (.setProperty (.-style el) "--stretch" (str (pure/offset pull) "px")))

(defn- rest! [^js el]
  (.remove (.-classList el) "stretch" "spring")
  (.removeProperty (.-style el) "--stretch"))

(defn- settle!
  "The push stopped: the messages spring back to their place, then the transform goes."
  [^js el state]
  (js/clearTimeout (:timer @state))
  (reset! state {:pull 0 :timer (js/setTimeout #(rest! el) spring-ms) :touch-y (:touch-y @state)})
  (.add (.-classList el) "spring")
  (.setProperty (.-style el) "--stretch" "0px"))

(defn- step!
  "A scroll of dy px at target: the pull grows, shrinks or ends (pure/next-pull), shown at once; a
  wheel's (settle? true) springs back settle-ms after its last step."
  [^js el state ^js target dy settle?]
  (let [{:keys [pull timer]} @state
        nxt (if (inner-scrolls? el target dy) 0 (pure/next-pull pull dy (pure/ends (numbers el))))]
    (cond (not (zero? nxt))
          (do (js/clearTimeout timer)
              (swap! state assoc :pull nxt :timer (when settle? (js/setTimeout #(settle! el state) settle-ms)))
              (show! el nxt))
          (not (zero? pull)) (settle! el state))))

(defn- on-wheel [^js el state]
  (fn [^js e]
    (when-not (.-ctrlKey e)
      (step! el state (.-target e) (pure/wheel-px (.-deltaY e) (.-deltaMode e) (.-clientHeight el)) true))))

(defn- touch-y [^js e] (some-> (aget (.-touches e) 0) .-clientY))

(defn- on-touch-move [^js el state]
  (fn [^js e]
    (let [y (touch-y e) before (:touch-y @state)]
      (swap! state assoc :touch-y y)
      (when (and y before) (step! el state (.-target e) (- before y) false)))))

(defn- listeners
  "The column's events and what each does."
  [^js el state]
  {"wheel" (on-wheel el state)
   "touchstart" #(swap! state assoc :touch-y (touch-y %))
   "touchmove" (on-touch-move el state)
   "touchend" #(when-not (zero? (:pull @state)) (settle! el state))
   "touchcancel" #(when-not (zero? (:pull @state)) (settle! el state))})

(defn- attach!
  "Listens on the column el (passive: the page never stops a scroll); returns what undoes it."
  [^js el]
  (let [state (atom {:pull 0})
        ls (listeners el state)]
    (.add (.-classList el) "band")
    (doseq [[k f] ls] (.addEventListener el k f #js {:passive true}))
    (fn []
      (doseq [[k f] ls] (.removeEventListener el k f #js {:passive true}))
      (js/clearTimeout (:timer @state))
      (rest! el)
      (.remove (.-classList el) "band"))))

(defn use-stretch!
  "The band on the scrolling column in box, set up again when deps change."
  [box deps]
  (h/use-effect
   (fn [] (when-let [el (and (drawn?) (.-current box))] (attach! el)))
   deps))
