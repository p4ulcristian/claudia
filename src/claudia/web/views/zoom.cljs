(ns claudia.web.views.zoom
  "The viewer's photo (README decision 84), fitted and centred in its stage, zoomable: two fingers
  pinch, a double tap (or double click) zooms in there and back, the wheel, a trackpad pinch (ctrl
  wheel; Safari's gesture events) zoom at the pointer, and one finger or the mouse pans it while
  zoomed, never off the stage. Unzoomed, one finger drags it: sideways to the next or previous
  photo, down to close. A tap beside it closes. The page itself never zooms (touch-action none, and
  claudia.web.viewport stops iOS's own pinch). The zoom lives in refs and goes straight onto the
  image's style (no render per move); the maths is claudia.web.views.pure.zoom, the swipe
  claudia.web.views.pure.viewer."
  (:require [claudia.web.views.pure.viewer :as pv]
            [claudia.web.views.pure.zoom :as z]
            [reagent.hooks :as h]))

(def ^:private double-ms 300)
(def ^:private tap-slop 8)
(def ^:private settle-ms 200)

(defn- from-centre
  "The event's point in px from the stage's centre (where the photo's centre sits)."
  [{:keys [stage]} ^js e]
  (let [r (.getBoundingClientRect ^js (.-current stage))]
    [(- (.-clientX e) (.-left r) (/ (.-width r) 2)) (- (.-clientY e) (.-top r) (/ (.-height r) 2))]))

(defn- size-of [^js el] [(.-offsetWidth el) (.-offsetHeight el)])

(defn- show!
  "css onto the photo's transform (eased when settle?), and whether it is zoomed onto the box."
  [{:keys [img box]} css zoomed? settle?]
  (when-let [^js el (.-current img)]
    (set! (.. el -style -transition) (if settle? (str "transform " settle-ms "ms ease-out") ""))
    (set! (.. el -style -transform) css))
  (some-> ^js (.-current box) .-classList (.toggle "zoomed" zoomed?)))

(defn- put!
  "Sets the zoom, kept over the stage, on the ref and the photo."
  ([refs next-z] (put! refs next-z false))
  ([{:keys [zoom img stage] :as refs} next-z settle?]
   (when-let [^js el (.-current img)]
     (let [kept (z/kept-in next-z (size-of el) (size-of (.-current stage)))]
       (set! (.-current zoom) kept)
       (show! refs (z/transform kept) (> (:k kept) 1) settle?)))))

(defn- far? [[x1 y1] [x2 y2]] (> (js/Math.hypot (- x2 x1) (- y2 y1)) tap-slop))

(defn- down! [{:keys [pointers gesture img] :as refs} ^js e]
  (when (zero? (.-button e))
    (.setPointerCapture (.-currentTarget e) (.-pointerId e))
    (let [^js ps (.-current pointers) pt (from-centre refs e)]
      (.set ps (.-pointerId e) pt)
      (set! (.-current gesture)
            (if (= 1 (.-size ps))
              {:start pt :on-photo (identical? (.-target e) (.-current img))}
              (assoc (.-current gesture) :moved true :pinch true))))))

(defn- dragged! [{:keys [props] :as refs} [sx sy] [x y]]
  (let [[ox oy] (pv/drag-offset (- x sx) (- y sy) (:n (.-current props)))]
    (show! refs (str "translate(" ox "px, " oy "px)") false false)))

(defn- move! [{:keys [pointers gesture zoom] :as refs} ^js e]
  (let [^js ps (.-current pointers) id (.-pointerId e)]
    (when-let [before (.get ps id)]
      (let [olds (vec (.values ps))
            now (from-centre refs e)
            g (cond-> (.-current gesture) (some-> (:start (.-current gesture)) (far? now)) (assoc :moved true))]
        (.set ps id now)
        (set! (.-current gesture) g)
        (cond
          (= 2 (count olds)) (put! refs (z/pinched (.-current zoom) olds (vec (.values ps))))
          (> (:k (.-current zoom)) 1) (put! refs (z/moved (.-current zoom) (- (first now) (first before))
                                                          (- (second now) (second before))))
          (and (:moved g) (not (:pinch g))) (dragged! refs (:start g) now))))))

(defn- tapped!
  "A tap: a second one soon after near the first zooms in or out there; a lone one beside the
  unzoomed photo closes."
  [{:keys [last-tap zoom props] :as refs} [x y :as pt] on-photo?]
  (let [t (js/performance.now) {:keys [at where]} (.-current last-tap)]
    (cond (and at (< (- t at) double-ms) (not (far? where pt)))
          (do (set! (.-current last-tap) nil) (put! refs (z/double-tapped (.-current zoom) x y) true))
          (or on-photo? (> (:k (.-current zoom)) 1)) (set! (.-current last-tap) {:at t :where pt})
          :else ((:on-backdrop (.-current props))))))

(defn- let-go!
  "An unzoomed drag let go: the next or previous photo, closed, or back in place."
  [{:keys [stage props] :as refs} [sx sy] [x y]]
  (let [{:keys [n on-swipe]} (.-current props)
        outcome (pv/swipe-outcome (- x sx) (- y sy) (first (size-of (.-current stage))) n)]
    (when-not (and outcome (on-swipe outcome))
      (put! refs z/none true))))

(defn- up! [{:keys [pointers gesture zoom] :as refs} ^js e]
  (let [^js ps (.-current pointers) g (.-current gesture)]
    (when (and (.delete ps (.-pointerId e)) (zero? (.-size ps)))
      (cond (not (:moved g)) (tapped! refs (from-centre refs e) (:on-photo g))
            (and (not (:pinch g)) (= 1 (:k (.-current zoom)))) (let-go! refs (:start g) (from-centre refs e))))))

(defn- cancelled!
  "The browser took a pointer back: forgotten, and an unzoomed photo goes back in place."
  [{:keys [pointers gesture zoom] :as refs} ^js e]
  (let [^js ps (.-current pointers)]
    (when (and (.delete ps (.-pointerId e)) (zero? (.-size ps)))
      (set! (.-current gesture) {})
      (when (= 1 (:k (.-current zoom))) (put! refs z/none true)))))

(defn- use-listeners!
  "Listeners React can't add as needed: the wheel (it must prevent the page's scroll) and Safari's
  trackpad pinch (gesture events, only while no finger is down: an iPad's touch pinch sends them too,
  and the pointers handle that)."
  [{:keys [box zoom pointers] :as refs}]
  (h/use-effect
   (fn []
     (let [^js el (.-current box)
           scale (atom 1)
           zoom-by (fn [^js e f] (let [[x y] (from-centre refs e)] (put! refs (z/zoom-at (.-current zoom) f x y))))
           ls {"wheel" (fn [^js e] (.preventDefault e) (zoom-by e (z/wheel-factor (.-deltaY e) (.-ctrlKey e))))
               "gesturestart" (fn [^js e] (.preventDefault e) (reset! scale 1))
               "gesturechange" (fn [^js e]
                                 (.preventDefault e)
                                 (when (zero? (.-size ^js (.-current pointers)))
                                   (zoom-by e (/ (.-scale e) @scale))
                                   (reset! scale (.-scale e))))}]
       (doseq [[t f] ls] (.addEventListener el t f #js {:passive false}))
       #(doseq [[t f] ls] (.removeEventListener el t f))))
   []))

(defn- use-refs [props]
  (let [p (h/use-ref props)]
    (set! (.-current p) props)
    {:zoom (h/use-ref z/none) :pointers (h/use-ref (js/Map.)) :gesture (h/use-ref {})
     :last-tap (h/use-ref nil) :box (h/use-ref nil) :stage (h/use-ref nil) :img (h/use-ref nil)
     :props p}))

(defn- set-ref [r] #(set! (.-current r) %))

(defn photo
  "The photo item ({:src :alt}) on the whole window. props: :n (how many items the viewer holds),
  :on-swipe (called with :next, :prev or :close; true when it did something, else the photo goes back
  in place), :on-backdrop (a tap beside the unzoomed photo)."
  [{:keys [src alt]} props]
  (let [{:keys [box stage img] :as refs} (use-refs props)]
    (use-listeners! refs)
    [:div.viewer-touch {:ref (set-ref box)
                        :on-pointer-down #(down! refs %) :on-pointer-move #(move! refs %)
                        :on-pointer-up #(up! refs %) :on-pointer-cancel #(cancelled! refs %)}
     [:div.viewer-stage {:ref (set-ref stage)}
      [:img.viewer-media {:ref (set-ref img) :src src :alt (or alt "") :draggable false}]]]))
