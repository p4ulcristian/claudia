(ns claudia.web.swipe
  "The phone's sideways swipes, following the finger like iOS's back swipe: on the chat, right
  slides it off over the list (which comes in from 30% to the left) and left pulls the picture in;
  on the list, left slides the open chat back over it; on the picture, right pushes it out; on
  Settings and an agent's view, right goes back. Released past 40% of the width, or flicked, the move completes; else
  it springs back. The page owns the gesture: it starts anywhere, except in fields, on what moves
  sideways itself (code and tables that scroll, pannable graphs, sliders, the 3D view), with two
  fingers, or on a mostly vertical move (the axis is decided on the first 10 px).

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

(defn- less-motion? [] (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)")))

(defn- moves-sideways?
  "Something under the finger moves sideways itself: a scroller with room, or an element that
  takes horizontal moves (touch-action without pan-x)."
  [el]
  (loop [^js el el]
    (if (or (nil? el) (identical? el js/document.body))
      false
      (let [s (js/getComputedStyle el)
            x (.-overflowX s)
            ta (.-touchAction s)]
        (if (or (and (#{"auto" "scroll" "overlay"} x) (> (.-scrollWidth el) (inc (.-clientWidth el))))
                (and ta (not= ta "auto") (not= ta "manipulation") (not (.includes ta "pan-x"))))
          true
          (recur (.-parentElement el)))))))

;; ---- the moves the screens make ----

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

(defn- gestures
  "Where a touch may start (surface) and which moves it may make there, for the screen shown now."
  []
  (let [$ #(js/document.querySelector %)
        ^js app ($ ".app")
        screen (some-> app .-dataset .-screen)
        on? #(= % (some-> ^js ($ ".app") .-dataset .-screen))
        side ($ ".app > .sidebar-slot")
        left ($ ".app .split > .left")
        right ($ ".app .split > .right")
        settings ($ ".st-pane")]
    (cond
      (nil? app) nil
      ;; Settings takes the list's place: right goes back to the chats.
      (and settings (= screen "list"))
      {:surface settings
       :moves (keep identity [(slide-back settings nil #(rf/dispatch [:ops/settings false])
                                          #(not (js/document.querySelector ".st-pane")))])}

      (= screen "list")
      {:surface side
       :moves (keep identity [(when (:open @rdb/app-db)
                                (slide-over left side #(rf/dispatch [:nav/screen :chat]) #(on? "chat")))])}

      ;; An agent's view lies over the chat: right goes back to the chat.
      (and (= screen "chat") ($ ".ag-screen"))
      {:surface ($ ".ag-screen")
       :moves (keep identity [(slide-back ($ ".ag-screen") nil #(rf/dispatch [:agents/close (:open @rdb/app-db)])
                                          #(not (js/document.querySelector ".ag-screen")))])}

      (= screen "chat")
      {:surface left
       :moves (keep identity [(slide-back left side #(rf/dispatch [:nav/list]) #(on? "list"))
                              (slide-over right left #(rf/dispatch [:nav/screen :picture]) #(on? "picture"))])}

      (= screen "picture")
      {:surface right
       :moves (keep identity [(slide-back right left #(rf/dispatch [:nav/screen :chat]) #(on? "chat"))])}

      :else nil)))

(defonce ^:private installed (atom false))

(defn install!
  "Turns the swipes on (they act only while the page is phone-wide)."
  []
  (when-not @installed
    (reset! installed true)
    (let [track (atom nil)
          busy (atom false)
          raf (atom 0)
          paint-soon (fn [g p]
                       (when-not (less-motion?)
                         (js/cancelAnimationFrame @raf)
                         (reset! raf (js/requestAnimationFrame #((:paint g) p)))))
          finish (fn [g from done? speed]
                   (js/cancelAnimationFrame @raf)
                   (if (less-motion?)
                     (do (when done? ((:commit g))) ((:clear g)))
                     (let [to (if done? 1 0)
                           ms (min 320 (max 120 (/ (* (js/Math.abs (- to from)) (:width g)) (max speed 0.9))))
                           t0 (js/performance.now)]
                       (reset! busy true)
                       (letfn [(step [now]
                                 (let [k (min 1 (/ (- now t0) ms))
                                       e (- 1 (js/Math.pow (- 1 k) 3))]
                                   ((:paint g) (+ from (* (- to from) e)))
                                   (cond
                                     (< k 1) (reset! raf (js/requestAnimationFrame step))
                                     (not done?) (do ((:clear g)) (reset! busy false))
                                     :else
                                     (do ((:commit g))
                                         ;; the classes take over where the paint ended, then the inline styles go
                                         (let [frames (atom 0)]
                                           (letfn [(wait [_]
                                                     (if (or ((:settled? g)) (> (swap! frames inc) 90))
                                                       (do ((:clear g)) (reset! busy false))
                                                       (reset! raf (js/requestAnimationFrame wait))))]
                                             (reset! raf (js/requestAnimationFrame wait))))))))]
                         (reset! raf (js/requestAnimationFrame step))))))
          cancel (fn []
                   (let [tr @track]
                     (reset! track nil)
                     (when (:g tr) (finish (:g tr) (:p tr) false 0))))]
      (js/window.addEventListener
       "touchstart"
       (fn [^js e]
         (reset! track nil)
         (when (and (db/phone?) (not @busy) (= 1 (.-length (.-touches e))))
           (let [t (aget (.-touches e) 0)
                 target (when (instance? js/Element (.-target e)) (.-target e))
                 {:keys [surface moves]} (gestures)]
             (when (and target surface (.contains surface target) (seq moves)
                        (not (.closest target "input, textarea, select, [contenteditable], [data-no-swipe]"))
                        (not (moves-sideways? target)))
               (reset! track {:x0 (.-clientX t) :y0 (.-clientY t) :origin (.-clientX t) :moves moves
                              :g nil :p 0 :samples []})))))
       #js {:passive true})
      (js/window.addEventListener
       "touchmove"
       (fn [^js e]
         (when-let [tr @track]
           (let [t (aget (.-touches e) 0)]
             (if (or (> (.-length (.-touches e)) 1) (not t))
               (cancel)
               (let [tr (if (:g tr)
                          tr
                          (let [dx (- (.-clientX t) (:x0 tr)) dy (- (.-clientY t) (:y0 tr))]
                            (cond
                              (< (js/Math.hypot dx dy) axis-px) nil
                              ;; mostly vertical, or a scroller already follows the finger: not ours
                              :else (let [g (when (and (>= (js/Math.abs dx) (* 1.2 (js/Math.abs dy)))
                                                       (.-cancelable e) (viewport/touch-blocked?))
                                              (some #(when (= (js/Math.sign dx) (:dir %)) %) (:moves tr)))]
                                      (if g
                                        (assoc tr :g g :origin (.-clientX t))
                                        (do (reset! track nil) nil))))))]
                 (when (:g tr)
                   (when (.-cancelable e) (.preventDefault e))
                   (let [g (:g tr)
                         p (min 1 (max 0 (/ (* (- (.-clientX t) (:origin tr)) (:dir g)) (:width g))))
                         now (or (.-timeStamp e) (js/performance.now))
                         samples (->> (conj (:samples tr) {:x (.-clientX t) :t now})
                                      (drop-while #(and (> (- now (:t %)) 100) true))
                                      vec)
                         samples (if (< (count samples) 2) (conj (vec (take-last 1 (:samples tr))) {:x (.-clientX t) :t now}) samples)]
                     (reset! track (assoc tr :p p :samples samples))
                     (paint-soon g p))))))))
       #js {:passive false})
      (js/window.addEventListener
       "touchend"
       (fn [_]
         (let [tr @track]
           (reset! track nil)
           (when-let [g (:g tr)]
             (let [s (:samples tr) a (first s) b (peek s)
                   v (if (and a b (> (:t b) (:t a))) (/ (* (- (:x b) (:x a)) (:dir g)) (- (:t b) (:t a))) 0)
                   done? (cond (> v flick) true (< v (- flick)) false :else (> (:p tr) done-at))]
               (finish g (:p tr) done? (js/Math.abs v)))))))
      (js/window.addEventListener "touchcancel" cancel))))
