(ns claudia.web.views.tearoff
  "A chat's circle torn out of the rail: dragged and dropped outside the window, the chat opens in a
  new window of its own (its address, /c/<server>/<chat>), as big as this one. The windows already
  know each other (claudia.web.tabs): the chat shows as open elsewhere."
  (:require [claudia.web.fx :as fx]))

;; A drag leaving the page within this many CSS px of the window's edge left the window (leaving
;; through the middle is entering a freeform block's frame).
(def ^:private edge 32)
;; What the drag carries: the chat's path, a type no field takes as text.
(def drag-type "application/x-claudia-chat")

;; ---- pure ----

(defn left-window?
  "A dragleave at (x, y) in a window w × h with no element left to (related? false) left the window:
  it happened at the window's edge or past it."
  [{:keys [x y w h related?]}]
  (and (not related?)
       (or (<= x edge) (<= y edge) (>= x (- w edge)) (>= y (- h edge)))))

(defn chat-url
  "The chat k's address on this origin."
  [origin k]
  (str origin (fx/path-of k)))

(defn features
  "window.open features: a popup (an app window, no tabs) as big as w × h."
  [w h]
  (str "popup,width=" (js/Math.round w) ",height=" (js/Math.round h)))

;; ---- edges ----

;; true while the drag is outside the window.
(defonce ^:private outside (atom false))

(defn- on-leave [^js e]
  (when (left-window? {:x (.-clientX e) :y (.-clientY e) :w js/window.innerWidth :h js/window.innerHeight
                       :related? (some? (.-relatedTarget e))})
    (reset! outside true)))

(defn- on-enter [_] (reset! outside false))

(defn- listen! [on?]
  (let [f (if on? #(.addEventListener js/document %1 %2) #(.removeEventListener js/document %1 %2))]
    (f "dragleave" on-leave)
    (f "dragenter" on-enter)))

(defn- start! [k ^js e]
  (reset! outside false)
  (listen! true)
  (let [dt (.-dataTransfer e)]
    (.setData dt drag-type (fx/path-of k))
    (set! (.-effectAllowed dt) "move")))

(defn- end! [k]
  (listen! false)
  (when @outside
    (reset! outside false)
    (js/window.open (chat-url js/location.origin k) "_blank" (features js/window.innerWidth js/window.innerHeight))))

(defn drag-props
  "What makes chat k's circle draggable out of the window."
  [k]
  {:draggable true
   :on-drag-start #(start! k %)
   :on-drag-end #(end! k)})
