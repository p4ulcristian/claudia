(ns claudia.web.views.rail-width
  "The rail's width (README decision 80): its right edge is dragged (a mouse or a finger) and the
  rail follows; let go, it snaps to the circles or to a card width (claudia.web.views.pure.rail/snap)
  and the width is kept on this device (localStorage). The window keeps the page its room: a kept
  width that does not fit shows at the widest that does."
  (:require [reagent.core :as r]
            [claudia.web.views.pure.rail :as pure]))

(def ^:private store-key "railW")

(defn- read-stored []
  (pure/parse-stored (try (js/localStorage.getItem store-key) (catch :default _ nil))))

;; {:stored (the snapped width kept on this device) :live (px while the edge is dragged, else nil)
;;  :win (the window's width, followed)}
(defonce ^:private state
  (if (exists? js/window)
    (let [s (r/atom {:stored (read-stored) :live nil :win js/window.innerWidth})]
      (js/window.addEventListener "resize" #(swap! s assoc :win js/window.innerWidth))
      s)
    (r/atom {:stored pure/narrow :live nil :win 0})))

(defn width
  "The rail's width now, px."
  []
  (let [{:keys [stored live win]} @state] (or live (pure/shown stored win))))

(defn cards? "Whether the rail shows cards now." [] (pure/cards? (width)))

(defn resizing? "Whether its edge is being dragged now." [] (some? (:live @state)))

(defn- settle!
  "The edge let go: the width snaps and is kept."
  []
  (let [{:keys [stored live win]} @state
        w (pure/snap (or live stored) (pure/max-width win))]
    (try (js/localStorage.setItem store-key (str w)) (catch :default _ nil))
    (swap! state assoc :stored w :live nil)))

(defn drag-edge!
  "pointerdown on the rail's edge: the rail (the edge's parent) follows the pointer until let go."
  [^js e]
  ;; A finger would scroll instead: the drag takes the pointer.
  (.preventDefault e)
  (.stopPropagation e)
  (.setPointerCapture (.-currentTarget e) (.-pointerId e))
  (let [left (.-left (.getBoundingClientRect (.. e -currentTarget -parentElement)))
        move (fn [^js m] (swap! state assoc :live (pure/dragged left (.-clientX m) (pure/max-width (:win @state)))))
        up (fn up []
             (js/window.removeEventListener "pointermove" move)
             (js/window.removeEventListener "pointerup" up)
             (js/window.removeEventListener "pointercancel" up)
             (settle!))]
    (swap! state assoc :live (width))
    (js/window.addEventListener "pointermove" move)
    (js/window.addEventListener "pointerup" up)
    (js/window.addEventListener "pointercancel" up)))

(defn edge
  "The rail's right edge, a handle 14 px wide over its chalk line."
  []
  [:div.rail-edge {:role "separator" :aria-orientation "vertical" :aria-label "Rail width"
                   :title "Drag: wider rail with cards"
                   :on-pointer-down drag-edge!}])
