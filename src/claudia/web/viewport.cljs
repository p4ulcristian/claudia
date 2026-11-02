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

(defn- can-scroll?
  "Whether some element from el up to the body can scroll by (dx, dy)."
  [el dx dy]
  (let [down? (>= (js/Math.abs dy) (js/Math.abs dx))]
    (loop [el el]
      (if (or (nil? el) (identical? el js/document.body))
        false
        (let [s (js/getComputedStyle el)
              ok (if down?
                   (and (scrolls? (.-overflowY s)) (> (.-scrollHeight el) (.-clientHeight el))
                        (if (pos? dy)
                          (< (+ (.-scrollTop el) (.-clientHeight el)) (dec (.-scrollHeight el)))
                          (pos? (.-scrollTop el))))
                   (and (scrolls? (.-overflowX s)) (> (.-scrollWidth el) (.-clientWidth el))
                        (if (pos? dx)
                          (< (+ (.-scrollLeft el) (.-clientWidth el)) (dec (.-scrollWidth el)))
                          (pos? (.-scrollLeft el)))))]
          (if ok true (recur (.-parentElement el))))))))

(defn install! []
  (when-let [vv js/window.visualViewport]
    (.addEventListener vv "resize" fit)
    (.addEventListener vv "scroll" fit)
    (js/window.addEventListener "scroll" fit)
    (fit))
  ;; Decided on a touch's first move: later moves can't be cancelled once a scroll started.
  (let [start (atom nil) blocked (atom false)]
    (js/window.addEventListener
     "touchstart"
     (fn [^js e]
       (let [t (aget (.-touches e) 0)]
         (reset! start (when (and t (= 1 (.-length (.-touches e)))) [(.-clientX t) (.-clientY t)]))
         (reset! blocked false)))
     #js {:passive true})
    (js/window.addEventListener
     "touchmove"
     (fn [^js e]
       (if (> (.-length (.-touches e)) 1)
         (.preventDefault e)
         (let [t (aget (.-touches e) 0)]
           (when (and (.-cancelable e) t)
             (when-let [[x y] @start]
               (let [dx (- x (.-clientX t)) dy (- y (.-clientY t))]
                 (when-not (and (zero? dx) (zero? dy))
                   (let [target (when (instance? js/Element (.-target e)) (.-target e))]
                     (reset! blocked (and (not (some-> target (.closest "input[type=range]")))
                                          (not (can-scroll? target dx dy))))
                     (reset! start nil)))))
             (when @blocked (.preventDefault e))))))
     #js {:passive false})
    (js/window.addEventListener "gesturestart" #(.preventDefault %))))
