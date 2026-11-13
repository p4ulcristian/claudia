(ns claudia.web.views.zoom
  "An image big over the whole window, zoomable: two fingers pinch, a double tap (or double click)
  zooms in there and back, the wheel or a trackpad pinch zooms at the pointer, and one finger or
  the mouse drags it while zoomed. A tap on the fitted image, the × or Escape closes it. The zoom
  lives in refs and goes straight onto the image's style (no render per move); the maths is
  claudia.web.views.pure.zoom."
  (:require [claudia.web.views.pure.zoom :as z]
            ["react-dom" :as react-dom]
            [reagent.core :as r]
            [reagent.hooks :as h]))

(def ^:private double-ms 300)
(def ^:private tap-slop 8)

(defn- from-centre "The event's point in px from the window's centre." [^js e]
  [(- (.-clientX e) (/ js/innerWidth 2)) (- (.-clientY e) (/ js/innerHeight 2))])

(defn- put!
  "Sets the zoom, kept over the window, on the ref and the image."
  [{:keys [zoom img box]} next-z]
  (when-let [^js el (.-current img)]
    (let [kept (z/kept-in next-z [(.-offsetWidth el) (.-offsetHeight el)] [js/innerWidth js/innerHeight])]
      (set! (.-current zoom) kept)
      (set! (.. el -style -transform) (z/transform kept))
      (some-> ^js (.-current box) .-classList (.toggle "zoomed" (> (:k kept) 1))))))

(defn- down! [{:keys [pointers gesture timer]} ^js e]
  (.setPointerCapture (.-currentTarget e) (.-pointerId e))
  (js/clearTimeout (.-current timer))
  (.set (.-current pointers) (.-pointerId e) (from-centre e))
  (set! (.-current gesture)
        (if (= 1 (.-size (.-current pointers))) {:start (from-centre e) :moved false} {:moved true})))

(defn- far? [[x1 y1] [x2 y2]] (> (js/Math.hypot (- x2 x1) (- y2 y1)) tap-slop))

(defn- move! [{:keys [pointers gesture zoom] :as refs} ^js e]
  (let [^js ps (.-current pointers) id (.-pointerId e)]
    (when-let [before (.get ps id)]
      (let [olds (vec (.values ps))
            now (from-centre e)]
        (.set ps id now)
        (cond
          (= 2 (count olds)) (put! refs (z/pinched (.-current zoom) olds (vec (.values ps))))
          (> (:k (.-current zoom)) 1) (put! refs (z/moved (.-current zoom) (- (first now) (first before))
                                                          (- (second now) (second before)))))
        (when (some-> (:start (.-current gesture)) (far? now))
          (set! (.-current gesture) {:moved true}))))))

(defn- tap!
  "A tap: the second one soon after, near the first, zooms; a lone one on the fitted image closes."
  [{:keys [last-tap timer zoom close] :as refs} pt]
  (let [t (js/performance.now) {:keys [at where]} (.-current last-tap)]
    (if (and at (< (- t at) double-ms) (not (far? where pt)))
      (do (set! (.-current last-tap) nil)
          (put! refs (z/double-tapped (.-current zoom) (first pt) (second pt))))
      (do (set! (.-current last-tap) {:at t :where pt})
          (when (= 1 (:k (.-current zoom)))
            (set! (.-current timer) (js/setTimeout close double-ms)))))))

(defn- up! [{:keys [pointers gesture] :as refs} ^js e]
  (let [^js ps (.-current pointers)]
    (when (.delete ps (.-pointerId e))
      (when (and (zero? (.-size ps)) (not (:moved (.-current gesture))))
        (tap! refs (from-centre e))))))

(defn- use-wheel!
  "The wheel and a trackpad pinch zoom at the pointer (a listener that may prevent the page's scroll)."
  [{:keys [box zoom] :as refs}]
  (h/use-effect
   (fn []
     (let [^js el (.-current box)
           f (fn [^js e]
               (.preventDefault e)
               (let [[x y] (from-centre e)]
                 (put! refs (z/zoom-at (.-current zoom) (z/wheel-factor (.-deltaY e) (.-ctrlKey e)) x y))))]
       (.addEventListener el "wheel" f #js {:passive false})
       #(.removeEventListener el "wheel" f)))
   []))

(defn- use-escape! [close]
  (h/use-effect
   (fn []
     (let [k (fn [e] (when (= "Escape" (.-key e)) (.stopImmediatePropagation e) (close)))]
       (js/window.addEventListener "keydown" k true)
       #(js/window.removeEventListener "keydown" k true)))
   []))

(defn- overlay [src {:keys [box img close timer] :as refs}]
  [:div.img-big {:ref #(set! (.-current box) %) :role "dialog" :aria-label "Image" :data-no-swipe true
                 :on-pointer-down #(down! refs %) :on-pointer-move #(move! refs %)
                 :on-pointer-up #(up! refs %) :on-pointer-cancel #(up! refs %)}
   [:img {:ref #(set! (.-current img) %) :src src :alt "" :draggable false}]
   [:button.img-big-close {:type "button" :aria-label "Close"
                           :on-pointer-down #(.stopPropagation %)
                           :on-click (fn [e] (.stopPropagation e) (js/clearTimeout (.-current timer)) (close))}
    "×"]])

(defn image-big
  "src big over the whole window, zoomable; close is called to close it."
  [src close]
  (let [refs {:zoom (h/use-ref z/none) :pointers (h/use-ref (js/Map.)) :gesture (h/use-ref {})
              :last-tap (h/use-ref nil) :timer (h/use-ref nil) :box (h/use-ref nil) :img (h/use-ref nil)
              :close close}]
    (use-escape! close)
    (use-wheel! refs)
    (h/use-effect (fn [] #(js/clearTimeout (.-current (:timer refs)))) [])
    (react-dom/createPortal (r/as-element (overlay src refs)) js/document.body)))
