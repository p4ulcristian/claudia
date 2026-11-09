(ns claudia.web.views.circle
  "Circling on the picture: hold the right button and draw; on release the circled zone becomes an
  image in the draft (claudia.web.capture takes it from the shared tab), with what lies under the
  chalk, and the chalk line stays on the picture until the draft is sent or the image removed.
  Shift + right click opens the browser's menu. A freeform block's frame forwards the right
  button's moves (claudia.web.views.frame's bridge), since its page swallows them."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.capture :as capture]
            [claudia.web.circle :as circle]))

(defn- box-of [^js el]
  (let [r (.getBoundingClientRect el)]
    {:x (.-left r) :y (.-top r) :w (.-width r) :h (.-height r)}))

(defn- seen-box
  "The window box where the picture shows: its scroll pane cut to the window."
  [^js stage]
  (let [pane (or (.closest stage ".right-scroll") stage)]
    (circle/clip (box-of pane) 0 {:x 0 :y 0 :w js/window.innerWidth :h js/window.innerHeight})))

(defn- on-screen
  "Stage points in the window's coordinates."
  [^js stage points]
  (let [{:keys [x y]} (box-of stage)]
    (circle/shift points x y)))

(defn- zone
  "The window box of a line on stage, padded, cut to what shows; nil when out of sight."
  [^js stage points]
  (when-let [seen (seen-box stage)]
    (circle/clip (circle/bbox (on-screen stage points)) circle/pad seen)))

(defn- block-of [^js el]
  (when-let [^js b (some-> el (.closest "[data-block]"))]
    (let [d (.-dataset b)]
      {:id (.-block d) :name (.-name d) :kind (.-kind d) :form (not-empty (.-form d))})))

(defn- top-in-stage [^js stage [x y]]
  (some #(when (and (.contains stage %) (not (.closest ^js % ".circle-layer"))) %)
        (array-seq (js/document.elementsFromPoint x y))))

(defn- under
  "What lies under the circle: the blocks it touches and the short texts there."
  [^js stage z]
  (let [els (keep #(top-in-stage stage %) (circle/grid z 7))]
    {:blocks (vec (distinct (keep block-of els)))
     :texts (vec (distinct (keep #(circle/label-text (.-textContent ^js %)) els)))}))

(defn- failed [^js e]
  (if (= "NotAllowedError" (.-name e))
    "Circling needs this tab shared: allow it in the browser's prompt, then circle again."
    (str "The circle could not be taken: " (.-message e))))

(defn- take!
  "A finished line: its image goes to the draft (draft-key), then the line leaves pending."
  [^js stage draft-key points done]
  (let [info (assoc (under stage (or (zone stage points) (circle/bbox (on-screen stage points)))) :points points)]
    (-> (capture/shot #(zone stage points))
        (.then (fn [file] (rf/dispatch [:ops/attach draft-key #js [file] {:circle info}])))
        (.catch (fn [e] (rf/dispatch [:ops/error (failed e)])))
        (.finally done))))

;; ---- the pointer ----

(defn- stage-point [^js stage x y]
  (let [b (box-of stage)] [(- x (:x b)) (- y (:y b))]))

(defn- pointer
  "What the right button does, on stage: start, add, end (take it when a line was drawn), cancel.
  live: a ref of the line drawn now; set-line and set-pending redraw."
  [^js stage dk live set-line set-pending]
  (let [put! (fn [pts] (set! (.-current live) pts) (set-line pts))]
    {:start (fn [x y] (put! [(stage-point stage x y)]))
     :add (fn [x y] (when-let [pts (.-current live)] (put! (conj pts (stage-point stage x y)))))
     :cancel (fn [] (put! nil))
     :end (fn []
            (when-let [pts (.-current live)]
              (put! nil)
              (when (circle/drawn? pts)
                (set-pending #(conj % pts))
                (take! stage (.-current dk) pts (fn [] (set-pending (fn [ps] (vec (remove #{pts} ps)))))))))}))

(defn- right-press? [^js e]
  (and (= 2 (.-button e)) (not (.-shiftKey e)) (= "mouse" (.-pointerType e))))

(defn- pane-listeners
  "The right pane's listeners (capture phase: a 3D scene never sees the right button's press)."
  [^js pane live {:keys [start add end cancel]}]
  {"pointerdown" (fn [^js e]
                   (when (right-press? e)
                     (.preventDefault e)
                     (.stopPropagation e)
                     (.setPointerCapture pane (.-pointerId e))
                     (start (.-clientX e) (.-clientY e))))
   "pointermove" (fn [^js e] (when (.-current live) (add (.-clientX e) (.-clientY e))))
   "pointerup" (fn [^js e] (when (and (.-current live) (= 2 (.-button e))) (.stopPropagation e) (end)))
   "pointercancel" (fn [_] (cancel))
   "contextmenu" (fn [^js e] (when-not (.-shiftKey e) (.preventDefault e)))})

(defn- frame-box
  "The window box of the pane's frame whose window is src, or nil."
  [^js pane src]
  (some #(when (identical? (.-contentWindow ^js %) src) (box-of %))
        (array-seq (.querySelectorAll pane "iframe"))))

(defn- frame-listener
  "Right-button moves a freeform block's page forwarded ({claudiaChalk: down|move|up, x, y})."
  [^js pane {:keys [start add end]}]
  (fn [^js e]
    (when-let [kind (some-> (.-data e) .-claudiaChalk)]
      (when-let [fb (frame-box pane (.-source e))]
        (let [{:keys [x y]} (circle/from-frame {:kind kind :x (.. e -data -x) :y (.. e -data -y)} fb)]
          (case kind "down" (start x y) "move" (add x y) "up" (end) nil))))))

(defn- listen!
  "Installs the listeners; returns what removes them."
  [^js stage live handlers]
  (let [pane (or (.closest stage ".right") stage)
        ls (pane-listeners pane live handlers)
        on-msg (frame-listener pane handlers)]
    (doseq [[t f] ls] (.addEventListener pane t f true))
    (js/window.addEventListener "message" on-msg)
    #(do (doseq [[t f] ls] (.removeEventListener pane t f true))
         (js/window.removeEventListener "message" on-msg))))

;; ---- the chalk ----

(defn- line [pts cls]
  [:path {:class cls :d (circle/path-d pts)}])

(defn- layer-view [draft-key]
  (let [svg (h/use-ref nil)
        live (h/use-ref nil)
        dk (h/use-ref draft-key)
        [drawing set-line] (h/use-state nil)
        [pending set-pending] (h/use-state [])
        kept (map (comp :points :circle) (circle/circles @(rf/subscribe [:attach draft-key])))]
    (set! (.-current dk) draft-key)
    (h/use-effect
     (fn []
       (let [stage (.-parentElement ^js (.-current svg))]
         (listen! stage live (pointer stage dk live set-line set-pending))))
     [])
    [:svg.circle-layer {:ref #(set! (.-current svg) %) :aria-hidden true}
     (for [[i pts] (map-indexed vector (concat kept pending))] ^{:key i} [line pts "kept"])
     (when drawing [line drawing "drawing"])]))

(defn layer
  "The chalk over the live picture: the line being drawn and the draft's circles."
  [draft-key]
  [:f> layer-view draft-key])
