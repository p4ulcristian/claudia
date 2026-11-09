(ns claudia.web.views.tearoff
  "A chat's circle torn out of the rail: dropped anywhere but the left side (the rail and the chat
  list) or outside the window, the chat opens in a new window of its own (its address,
  /c/<server>/<chat>), as big as this one. When it was the chat open here, this window moves on to a
  chat no other window shows (or a new chat). The windows know each other (claudia.web.tabs)."
  (:require [re-frame.core :as rf]
            [claudia.web.fx :as fx]))

;; A drag leaving the page within this many CSS px of the window's edge left the window (leaving
;; through the middle is entering a freeform block's frame).
(def ^:private edge 32)
;; What the drag carries: the chat's path, a type no field takes as text.
(def drag-type "application/x-claudia-chat")
;; The left side: a drop there keeps the chat here.
(def ^:private keep-zone ".sidebar-slot")

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

(defn next-free
  "The first chat of rows (maps with :server :id) that is not torn (k) and no other window shows
  (elsewhere: [server chat] keys); nil when there is none."
  [rows elsewhere k]
  (some (fn [{:keys [server id]}]
          (let [r [server id]] (when-not (or (= r k) (contains? elsewhere r)) r)))
        rows))

;; ---- edges ----

;; The drag now: {:outside? (past the window's edge) :dropped? (dropped on the page, not the left side)}.
(defonce ^:private drag (atom {}))

(defn- kept-here? [^js e]
  (boolean (some-> (.-target e) (.closest keep-zone))))

(defn- on-leave [^js e]
  (when (left-window? {:x (.-clientX e) :y (.-clientY e) :w js/window.innerWidth :h js/window.innerHeight
                       :related? (some? (.-relatedTarget e))})
    (swap! drag assoc :outside? true)))

(defn- on-enter [_] (swap! drag assoc :outside? false))

(defn- on-over
  "Over the page but the left side, a drop is welcome."
  [^js e]
  (when-not (kept-here? e)
    (.preventDefault e)
    (set! (.. e -dataTransfer -dropEffect) "move")))

(defn- on-drop [^js e]
  (when-not (kept-here? e)
    (.preventDefault e)
    (swap! drag assoc :dropped? true)))

(def ^:private listeners
  {"dragleave" on-leave "dragenter" on-enter "dragover" on-over "drop" on-drop})

(defn- listen! [on?]
  (doseq [[t f] listeners]
    (if on? (.addEventListener js/document t f) (.removeEventListener js/document t f))))

(defn- start! [k ^js e]
  (reset! drag {})
  (listen! true)
  (let [dt (.-dataTransfer e)]
    (.setData dt drag-type (fx/path-of k))
    (set! (.-effectAllowed dt) "move")))

(defn- tear! [k move-on]
  (js/window.open (chat-url js/location.origin k) "_blank" (features js/window.innerWidth js/window.innerHeight))
  (move-on))

(defn- end! [k move-on]
  (listen! false)
  (let [{:keys [outside? dropped?]} @drag]
    (reset! drag {})
    (when (or outside? dropped?) (tear! k move-on))))

(defn move-on!
  "After chat k is torn out: when it was open here (open), open the first chat of rows no other
  window shows, or a new chat."
  [k open rows elsewhere]
  (when (= k open)
    (rf/dispatch (if-let [n (next-free rows elsewhere k)] [:nav/open n] [:nav/new]))))

(defn drag-props
  "What makes chat k's circle tear out of the window; move-on runs after it opened elsewhere."
  [k move-on]
  {:draggable true
   :on-drag-start #(start! k %)
   :on-drag-end #(end! k move-on)})
