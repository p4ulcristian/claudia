(ns claudia.web.viewport
  "The page never moves; only its scrollers do.

  The body is the page's one layer, in normal flow (iOS Safari paints its bottom bar solid behind
  any fixed element along the bottom edge). Without a keyboard the layer is the layout viewport.
  While a phone's on-screen keyboard is up, iOS shrinks (and pans) only the visual viewport, so the
  layer follows the visual viewport's size and offset (--vv-*) and the composer sits right on the
  keyboard.

  iOS also lets a finger pan the page itself whatever its overflow says, so a touch may only move
  something that can scroll that way; otherwise the move is cancelled. Pinch zoom is cancelled too.")

(def ^:private vars ["--vv-w" "--vv-h" "--vv-left" "--vv-top"])

(defn- fit []
  (let [vv js/window.visualViewport
        root js/document.documentElement
        style (.-style root)
        keyboard? (and vv (> (- (.-clientHeight root) (.-height vv)) 120))]
    (.toggleAttribute root "data-keyboard" (boolean keyboard?))
    (if (or (not vv) (not keyboard?) (> (js/Math.abs (- (.-scale vv) 1)) 0.01))
      (do (doseq [v vars] (.removeProperty style v))
          (when (or (pos? js/window.scrollX) (pos? js/window.scrollY))
            (js/window.scrollTo 0 0)))
      (do (.setProperty style "--vv-w" (str (.-width vv) "px"))
          (.setProperty style "--vv-h" (str (.-height vv) "px"))
          (.setProperty style "--vv-left" (str (.-pageLeft vv) "px"))
          (.setProperty style "--vv-top" (str (.-pageTop vv) "px"))))))

(defn- scrolls? [v] (contains? #{"auto" "scroll" "overlay"} v))

(defn scrolls-by?
  "Whether a box (its overflow styles and scroll sizes, as plain data) can scroll by (dx, dy): down
  or up for a mostly vertical move, else sideways."
  [{:keys [overflow-x overflow-y scroll-top scroll-left scroll-width scroll-height client-width client-height]} dx dy]
  (if (>= (js/Math.abs dy) (js/Math.abs dx))
    (and (scrolls? overflow-y) (> scroll-height client-height)
         (if (pos? dy)
           (< (+ scroll-top client-height) (dec scroll-height))
           (pos? scroll-top)))
    (and (scrolls? overflow-x) (> scroll-width client-width)
         (if (pos? dx)
           (< (+ scroll-left client-width) (dec scroll-width))
           (pos? scroll-left)))))

(defn- box-of [^js el]
  (let [s (js/getComputedStyle el)]
    {:overflow-x (.-overflowX s) :overflow-y (.-overflowY s)
     :scroll-top (.-scrollTop el) :scroll-left (.-scrollLeft el)
     :scroll-width (.-scrollWidth el) :scroll-height (.-scrollHeight el)
     :client-width (.-clientWidth el) :client-height (.-clientHeight el)}))

(defn- can-scroll?
  "Whether some element from el up to the body can scroll by (dx, dy)."
  [el dx dy]
  (loop [^js el el]
    (cond
      (or (nil? el) (identical? el js/document.body)) false
      (scrolls-by? (box-of el) dx dy) true
      :else (recur (.-parentElement el)))))

(defonce ^:private blocked
  ;; The current touch may move nothing native (decided on its first move): a swipe may take it.
  (atom false))

(defn touch-blocked? [] @blocked)

(defonce ^:private start
  ;; Where the current one-finger touch began, until its first move decided it.
  (atom nil))

(defn- on-touchstart [^js e]
  (let [t (aget (.-touches e) 0)]
    (reset! start (when (and t (= 1 (.-length (.-touches e)))) [(.-clientX t) (.-clientY t)]))
    (reset! blocked false)))

(defn- decide!
  "On the touch's first real move: blocked unless something under it scrolls that way (or it is on
  a slider)."
  [^js e [x y] ^js t]
  (let [dx (- x (.-clientX t)) dy (- y (.-clientY t))]
    (when-not (and (zero? dx) (zero? dy))
      (let [target (when (instance? js/Element (.-target e)) (.-target e))]
        (reset! blocked (and (not (some-> target (.closest "input[type=range]")))
                             (not (can-scroll? target dx dy))))
        (reset! start nil)))))

(defn- on-touchmove [^js e]
  (if (> (.-length (.-touches e)) 1)
    (.preventDefault e)
    (let [t (aget (.-touches e) 0)]
      (when (and (.-cancelable e) t)
        (when-let [xy @start] (decide! e xy t))
        (when @blocked (.preventDefault e))))))

(defn install! []
  (when-let [vv js/window.visualViewport]
    (.addEventListener vv "resize" fit)
    (.addEventListener vv "scroll" fit)
    (js/window.addEventListener "scroll" fit)
    (fit))
  ;; Decided on a touch's first move: later moves can't be cancelled once a scroll started.
  (js/window.addEventListener "touchstart" on-touchstart #js {:passive true})
  (js/window.addEventListener "touchmove" on-touchmove #js {:passive false})
  (js/window.addEventListener "gesturestart" #(.preventDefault %)))
