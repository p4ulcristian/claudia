(ns claudia.web.swipe
  "The phone's sideways swipes, following the finger like iOS's back swipe: on the chat, right
  slides it off over the list (which comes in from 30% to the left) and left pulls the picture in;
  on the list, left slides the open chat back over it; on the picture, right pushes it out; on
  Settings and an agent's view, right goes back. Released past 40% of the width, or flicked, the move completes; else
  it springs back. The page owns the gesture: it starts anywhere, except in fields, on what moves
  sideways itself (code and tables that scroll, pannable graphs, sliders, the 3D view), with two
  fingers, or on a mostly vertical move (the axis is decided on the first 10 px).

  A trackpad's two-finger sideways scroll (wheel events) makes the same moves in a phone-wide
  window: the scroll is the finger, scrolling right goes the way a left swipe goes (the scroll
  setting, natural or not, decides which way the fingers move for it, as on any wide page). A
  burst of wheel events with no gap over burst-gap-ms is one gesture; it is released once the
  wheel stays quiet for release-ms (the trackpad's own momentum counts as the finger).

  While the finger moves only inline transforms change, once a frame; at the end they are animated
  to where the screen's classes put that screen, the app steps (the same events the buttons
  dispatch), and once the classes show the end the inline styles go: nothing jumps. With reduced
  motion nothing follows the finger: a completed swipe just switches."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [claudia.web.db :as db]
            [claudia.web.viewport :as viewport]))

(def ^:private axis-px 10)
(def ^:private done-at 0.4)
(def ^:private flick 0.45) ; px per ms
(def ^:private burst-gap-ms 180)
(def ^:private release-ms 120)

(defn- less-motion? [] (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))

;; ---- the decisions, on plain numbers ----

(defn takes-sideways?
  "A box (its overflow-x and touch-action styles and widths, as plain data) moves sideways itself:
  a scroller with room, or one that takes horizontal moves (touch-action without pan-x)."
  [{:keys [overflow-x touch-action scroll-width client-width]}]
  (boolean
   (or (and (#{"auto" "scroll" "overlay"} overflow-x) (> scroll-width (inc client-width)))
       (and touch-action (not= touch-action "auto") (not= touch-action "manipulation")
            (not (.includes touch-action "pan-x"))))))

(defn pick-move
  "The move a finger that went (dx, dy) makes: the one in its direction, when the move is mostly
  sideways and takeable? (cancelable, and nothing native moves with it); nil otherwise."
  [moves dx dy takeable?]
  (when (and (>= (js/Math.abs dx) (* 1.2 (js/Math.abs dy))) takeable?)
    (some #(when (= (js/Math.sign dx) (:dir %)) %) moves)))

(defn progress
  "How far along a move is, 0..1: the finger at x, from origin, in the move's direction dir."
  [origin x dir width]
  (min 1 (max 0 (/ (* (- x origin) dir) width))))

(defn recent-samples
  "The finger's positions of about the last 100 ms, now at x: at least two (the last one before
  stays when nothing else is that recent)."
  [samples x now]
  (let [recent (->> (conj samples {:x x :t now})
                    (drop-while #(> (- now (:t %)) 100))
                    vec)]
    (if (< (count recent) 2) (conj (vec (take-last 1 samples)) {:x x :t now}) recent)))

(defn velocity
  "The finger's speed in the move's direction, px per ms, over the samples; 0 without two times."
  [samples dir]
  (let [a (first samples) b (peek samples)]
    (if (and a b (> (:t b) (:t a))) (/ (* (- (:x b) (:x a)) dir) (- (:t b) (:t a))) 0)))

(defn completes?
  "A released move completes when flicked forward, springs back when flicked back, else completes
  past done-at."
  [v p]
  (cond (> v flick) true (< v (- flick)) false :else (> p done-at)))

(defn animation-ms
  "How long the rest of a move takes, from from to to over width px at the finger's speed."
  [from to width speed]
  (min 320 (max 120 (/ (* (js/Math.abs (- to from)) width) (max speed 0.9)))))

(defn ease-out [k] (- 1 (js/Math.pow (- 1 k) 3)))

(defn gesture-kind
  "Which swipes the screen shown now has: Settings lies over the list, an agent's view over the chat."
  [screen settings? agent?]
  (cond
    (and settings? (= screen "list")) :settings
    (= screen "list") :list
    (and (= screen "chat") agent?) :agent
    (= screen "chat") :chat
    (= screen "picture") :picture))

;; ---- the wheel as a finger ----

(defn wheel-px
  "A wheel delta in px: delta-mode 0 is px, 1 lines (16 px), 2 pages (page px)."
  [delta delta-mode page]
  (case delta-mode 1 (* delta 16) 2 (* delta page) delta))

(defn new-burst?
  "A wheel event at now starts a new gesture when the one before (at prev, nil for none) is over
  burst-gap-ms ago."
  [prev now]
  (or (nil? prev) (> (- now prev) burst-gap-ms)))

(defn wheel-finger
  "Where the wheel's finger is after a scroll by (dx, dy) px: scrolling right moves it left."
  [{:keys [x y]} dx dy]
  {:x (- x dx) :y (- y dy)})

;; ---- the moves the screens make ----

(defn- moves-sideways?
  "Something under the finger moves sideways itself."
  [el]
  (loop [^js el el]
    (cond
      (or (nil? el) (identical? el js/document.body)) false
      (let [s (js/getComputedStyle el)]
        (takes-sideways? {:overflow-x (.-overflowX s) :touch-action (.-touchAction s)
                          :scroll-width (.-scrollWidth el) :client-width (.-clientWidth el)})) true
      :else (recur (.-parentElement el)))))

(defn- px [x] (str "translate3d(" x "px, 0, 0)"))

(defn- set-styles! [^js el styles]
  (when el
    (set! (.. el -style -transition) "none")
    (doseq [[k v] styles] (aset (.-style el) (name k) v))))

(defn- unset! [& els]
  (doseq [^js el els :when el]
    (let [s (.-style el)]
      (set! (.-transition s) "none")
      (set! (.-transform s) "")
      (set! (.-visibility s) "")
      (set! (.-boxShadow s) "")
      (.-offsetWidth el)
      (set! (.-transition s) ""))))

(defn- slide-back
  "A screen on top slides off to the right with the finger and shows the one under it, which
  comes from 30% to the left."
  [^js top ^js under back settled?]
  (when top
    (let [w (or (let [w (.-width (.getBoundingClientRect top))] (when (pos? w) w)) js/innerWidth)]
      {:dir 1 :width w
       :paint (fn [p]
                (set-styles! top {:transform (px (* p w)) :visibility "visible"})
                (set-styles! under {:transform (px (* (dec p) 0.3 w)) :visibility "visible"}))
       :commit back :settled? settled?
       :clear #(unset! top under)})))

(defn- slide-over
  "slide-back's mirror: the screen that slid off comes back from the right with a left move, and
  the one under it goes back to 30% to the left."
  [^js top ^js under forward settled?]
  (when top
    (let [w (or (some-> under .getBoundingClientRect .-width (#(when (pos? %) %))) js/innerWidth)]
      {:dir -1 :width w
       :paint (fn [p]
                (set-styles! top {:transform (px (* (- 1 p) w)) :visibility "visible"})
                (set-styles! under {:transform (px (* p -0.3 w)) :visibility "visible"}))
       :commit forward :settled? settled?
       :clear #(unset! top under)})))

(defn- $ [selector] (js/document.querySelector selector))

(defn- on? [screen] (= screen (some-> ^js ($ ".app") .-dataset .-screen)))

(defn- moves-for
  "Where a touch may start (surface) and which moves it may make there, for a kind of screen."
  [kind]
  (let [side ($ ".app > .sidebar-slot")
        left ($ ".app .split > .left")
        right ($ ".app .split > .right")]
    (case kind
      :settings {:surface ($ ".st-pane")
                 :moves [(slide-back ($ ".st-pane") ($ ".cs-pane") #(rf/dispatch [:ops/settings false])
                                     #(not (.contains (.-classList ^js ($ ".app")) "settings-shown")))]}
      :list {:surface side
             :moves [(when (:open @rdb/app-db)
                       (slide-over left side #(rf/dispatch [:nav/screen :chat]) #(on? "chat")))]}
      :agent {:surface ($ ".ag-screen")
              :moves [(slide-back ($ ".ag-screen") nil #(rf/dispatch [:agents/close (:open @rdb/app-db)])
                                  #(not ($ ".ag-screen")))]}
      :chat {:surface left
             :moves [(slide-back left side #(rf/dispatch [:nav/list]) #(on? "list"))
                     (slide-over right left #(rf/dispatch [:nav/screen :picture]) #(on? "picture"))]}
      :picture {:surface right
                :moves [(slide-back right left #(rf/dispatch [:nav/screen :chat]) #(on? "chat"))]})))

(defn- gestures
  "Where a touch may start (surface) and which moves it may make there, for the screen shown now."
  []
  (when-let [^js app ($ ".app")]
    (let [settings? (boolean (and (.contains (.-classList app) "settings-shown") ($ ".st-pane")))
          kind (gesture-kind (some-> app .-dataset .-screen) settings? (some? ($ ".ag-screen")))]
      (when kind
        (update (moves-for kind) :moves #(keep identity %))))))

;; ---- following the finger ----

(defonce ^:private installed (atom false))
(defonce ^:private track (atom nil)) ; the touch followed now: {:x0 :y0 :origin :moves :g :p :samples}
(defonce ^:private busy (atom false)) ; a move is animating to its end
(defonce ^:private raf (atom 0))

(defn- paint-soon! [g p]
  (when-not (less-motion?)
    (js/cancelAnimationFrame @raf)
    (reset! raf (js/requestAnimationFrame #((:paint g) p)))))

(defn- settle!
  "After a completed move the classes take over where the paint ended, then the inline styles go
  (at the latest after 90 frames). n: the frames waited so far."
  [g n]
  (if (or ((:settled? g)) (> n 90))
    (do ((:clear g)) (reset! busy false))
    (reset! raf (js/requestAnimationFrame #(settle! g (inc n))))))

(defn- animate!
  "The move animated from from to its end (done?) or back to its start."
  [g from done? speed]
  (let [to (if done? 1 0)
        ms (animation-ms from to (:width g) speed)
        t0 (js/performance.now)]
    (reset! busy true)
    (letfn [(step [now]
              (let [k (min 1 (/ (- now t0) ms))]
                ((:paint g) (+ from (* (- to from) (ease-out k))))
                (cond
                  (< k 1) (reset! raf (js/requestAnimationFrame step))
                  (not done?) (do ((:clear g)) (reset! busy false))
                  :else (do ((:commit g))
                            (reset! raf (js/requestAnimationFrame #(settle! g 1)))))))]
      (reset! raf (js/requestAnimationFrame step)))))

(defn- finish! [g from done? speed]
  (js/cancelAnimationFrame @raf)
  (if (less-motion?)
    (do (when done? ((:commit g))) ((:clear g)))
    (animate! g from done? speed)))

(defn- cancel! []
  (let [tr @track]
    (reset! track nil)
    (when (:g tr) (finish! (:g tr) (:p tr) false 0))))

(defn- start-track
  "The track a gesture starting at (x, y) on e's target gets, or nil when no move may start there."
  [^js e x y]
  (let [target (when (instance? js/Element (.-target e)) (.-target e))
        {:keys [surface moves]} (gestures)]
    (when (and target surface (.contains surface target) (seq moves)
               (not (.closest target "input, textarea, select, [contenteditable], [data-no-swipe]"))
               (not (moves-sideways? target)))
      {:x0 x :y0 y :origin x :moves moves :g nil :p 0 :samples []})))

(defn- on-touchstart [^js e]
  (reset! track nil)
  (when (and (db/phone?) (not @busy) (= 1 (.-length (.-touches e))))
    (let [t (aget (.-touches e) 0)]
      (reset! track (start-track e (.-clientX t) (.-clientY t))))))

(defn- taken
  "The track once the finger (at x, y) went axis-px: with its move picked, or nil (not yet; or not
  ours, a mostly vertical move or one a scroller already follows: then the track ends). free?:
  nothing native moves with the gesture."
  [tr ^js e x y free?]
  (let [dx (- x (:x0 tr)) dy (- y (:y0 tr))]
    (when (>= (js/Math.hypot dx dy) axis-px)
      (if-let [g (pick-move (:moves tr) dx dy (and (.-cancelable e) free?))]
        (assoc tr :g g :origin x)
        (do (reset! track nil) nil)))))

(defn- follow! [tr ^js e x]
  (when (.-cancelable e) (.preventDefault e))
  (let [g (:g tr)
        p (progress (:origin tr) x (:dir g) (:width g))
        now (or (.-timeStamp e) (js/performance.now))]
    (reset! track (assoc tr :p p :samples (recent-samples (:samples tr) x now)))
    (paint-soon! g p)))

(defn- on-touchmove [^js e]
  (when-let [tr @track]
    (let [t (aget (.-touches e) 0)]
      (if (or (> (.-length (.-touches e)) 1) (not t))
        (cancel!)
        (when-let [moving (if (:g tr) tr (taken tr e (.-clientX t) (.-clientY t) (viewport/touch-blocked?)))]
          (follow! moving e (.-clientX t)))))))

(defn- release! []
  (let [tr @track]
    (reset! track nil)
    (when-let [g (:g tr)]
      (let [v (velocity (:samples tr) (:dir g))]
        (finish! g (:p tr) (completes? v (:p tr)) (js/Math.abs v))))))

(defn- on-touchend [_]
  (when-not (:wheel @track) (release!)))

;; ---- the trackpad ----

(defonce ^:private wheel (atom {:prev nil :ours? false :timer nil})) ; the wheel burst going on now

(defn- wheel-start
  "A new burst: a track at the finger's start (0, 0), when the screen shown has a move there."
  [^js e]
  (when (and (db/phone?) (not @busy) (not (.-ctrlKey e)) (not (:g @track)))
    (some-> (start-track e 0 0) (assoc :wheel {:x 0 :y 0}))))

(defn- wheel-move!
  "The burst's track follows the wheel's finger, as a touch does."
  [tr ^js e]
  (let [page js/innerWidth
        f (wheel-finger (:wheel tr) (wheel-px (.-deltaX e) (.-deltaMode e) page)
                        (wheel-px (.-deltaY e) (.-deltaMode e) page))
        tr (assoc tr :wheel f)]
    (if (:g tr)
      (follow! tr e (:x f))
      (do (reset! track tr)
          (some-> (taken tr e (:x f) (:y f) true) (follow! e (:x f)))))))

(defn- swallow!
  "A burst whose move ended (or is animating) keeps its sideways tail from scrolling anything."
  [^js e]
  (when (and (.-cancelable e) (> (js/Math.abs (.-deltaX e)) (js/Math.abs (.-deltaY e))))
    (.preventDefault e)))

(defn- on-wheel [^js e]
  (let [now (js/performance.now)
        {:keys [prev ours? timer]} @wheel
        fresh? (new-burst? prev now)
        tr (if fresh? (wheel-start e) (when (:wheel @track) @track))]
    (js/clearTimeout timer)
    (cond
      tr (wheel-move! tr e)
      (and ours? (not fresh?)) (swallow! e))
    (reset! wheel {:prev now
                   :ours? (boolean (or (:g @track) (and ours? (not fresh?))))
                   :timer (js/setTimeout #(when (:wheel @track) (release!)) release-ms)})))

(defn install!
  "Turns the swipes and trackpad swipes on (they act only while the page is phone-wide)."
  []
  (when-not @installed
    (reset! installed true)
    (js/window.addEventListener "touchstart" on-touchstart #js {:passive true})
    (js/window.addEventListener "touchmove" on-touchmove #js {:passive false})
    (js/window.addEventListener "touchend" on-touchend)
    (js/window.addEventListener "touchcancel" #(cancel!))
    (js/window.addEventListener "wheel" on-wheel #js {:passive false})))
