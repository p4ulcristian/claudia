(ns claudia.web.views.circle
  "Circling on the picture or in the chat: hold the right button and draw; on release the circled
  zone becomes an image in the draft (claudia.web.capture takes it from the shared tab), with what
  lies under the chalk, and the chalk line stays where it was drawn until the draft is sent or the
  image removed. Shift + right click opens the browser's menu, and so does a right click in a text
  field. A freeform block's frame forwards the right button's moves (claudia.web.views.frame's
  bridge), since its page swallows them."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.capture :as capture]
            [claudia.web.circle :as circle]
            [claudia.web.db :as db]))

;; Where circles are drawn. The layer sits in the surface's content (its stage: the picture's
;; .stage, the chat's .messages); pane: where its listeners go; start: where a press starts a line;
;; seen: what shows of it.
(def ^:private surfaces
  {"picture" {:pane ".right" :start ".right" :seen ".right-scroll"}
   "chat" {:pane ".left" :start ".messages" :seen ".messages"}})

;; A right click here keeps the browser's menu (paste, spelling).
(def ^:private text-field "input, textarea, select, [contenteditable]")

(defn- box-of [^js el]
  (let [r (.getBoundingClientRect el)]
    {:x (.-left r) :y (.-top r) :w (.-width r) :h (.-height r)}))

(defn- origin
  "Where the stage's content starts in the window: its box, less what it scrolled itself (the chat
  scrolls its own content; the picture's stage moves inside its scroller)."
  [^js stage]
  (let [{:keys [x y]} (box-of stage)]
    {:x (- x (.-scrollLeft stage)) :y (- y (.-scrollTop stage))}))

(defn- seen-box
  "The window box where the stage shows: its scroller cut to the window."
  [^js stage seen-sel]
  (let [scroller (or (.closest stage seen-sel) stage)]
    (circle/clip (box-of scroller) 0 {:x 0 :y 0 :w js/window.innerWidth :h js/window.innerHeight})))

(defn- on-screen
  "Stage points in the window's coordinates."
  [^js stage points]
  (let [{:keys [x y]} (origin stage)]
    (circle/shift points x y)))

(defn- zone
  "The window box of a line on stage, padded, cut to what shows; nil when out of sight."
  [^js stage seen-sel points]
  (when-let [seen (seen-box stage seen-sel)]
    (circle/clip (circle/bbox (on-screen stage points)) circle/pad seen)))

(defn- block-of [^js el]
  (when-let [^js b (some-> el (.closest "[data-block]"))]
    (let [d (.-dataset b)]
      {:id (.-block d) :name (.-name d) :kind (.-kind d) :form (not-empty (.-form d))})))

(defn- top-in-stage [^js stage [x y]]
  (some #(when (and (.contains stage %) (not (.closest ^js % ".circle-layer"))) %)
        (array-seq (js/document.elementsFromPoint x y))))

(defn- text-of
  "An element's text as shown (line breaks where the layout breaks); SVG text has only textContent."
  [^js el]
  (or (.-innerText el) (.-textContent el)))

(defn- under
  "What lies under the circle: the blocks it touches and the texts there (the outermost of each)."
  [^js stage z]
  (let [els (distinct (keep #(top-in-stage stage %) (circle/grid z 7)))]
    {:blocks (vec (distinct (keep block-of els)))
     :texts (circle/outermost (keep #(circle/label-text (text-of %)) els))}))

(defn- failed [^js e]
  (if (= "NotAllowedError" (.-name e))
    "Circling needs this tab shared: allow it in the browser's prompt, then circle again."
    (str "The circle could not be taken: " (.-message e))))

(defn- take!
  "A finished line on surface: its image goes to the draft (draft-key), then the line leaves pending."
  [^js stage surface draft-key points done]
  (let [seen-sel (:seen (surfaces surface))
        z (or (zone stage seen-sel points) (circle/bbox (on-screen stage points)))
        info (assoc (under stage z) :points points :surface surface)]
    (-> (capture/shot #(zone stage seen-sel points))
        (.then (fn [file] (rf/dispatch [:ops/attach draft-key #js [file] {:circle info}])))
        (.catch (fn [e] (rf/dispatch [:ops/error (failed e)])))
        (.finally done))))

;; ---- the pointer ----

(defn- stage-point [^js stage x y]
  (let [o (origin stage)] [(- x (:x o)) (- y (:y o))]))

(defn- pointer
  "What the right button does, on stage: start, add, end (take it when a line was drawn), cancel.
  live: a ref of the line drawn now; set-line and set-pending redraw."
  [^js stage surface dk live set-line set-pending]
  (let [put! (fn [pts] (set! (.-current live) pts) (set-line pts))]
    {:start (fn [x y] (put! [(stage-point stage x y)]))
     :add (fn [x y] (when-let [pts (.-current live)] (put! (conj pts (stage-point stage x y)))))
     :cancel (fn [] (put! nil))
     :end (fn []
            (when-let [pts (.-current live)]
              (put! nil)
              (when (circle/drawn? pts)
                (set-pending #(conj % pts))
                (take! stage surface (.-current dk) pts (fn [] (set-pending (fn [ps] (vec (remove #{pts} ps)))))))))}))

(defn- in-text-field? [^js e]
  (boolean (some-> (.-target e) (.closest text-field))))

(defn- right-press?
  "A right press that starts a line: not with Shift, not in a text field, inside start-sel."
  [^js e start-sel]
  (and (= 2 (.-button e)) (not (.-shiftKey e)) (= "mouse" (.-pointerType e))
       (not (in-text-field? e)) (some? (some-> (.-target e) (.closest start-sel)))))

(defn- pane-listeners
  "The pane's listeners (capture phase: a 3D scene never sees the right button's press). armed: a
  ref, true from a press that started a line until its menu event."
  [^js pane start-sel live armed {:keys [start add end cancel]}]
  {"pointerdown" (fn [^js e]
                   (set! (.-current armed) (right-press? e start-sel))
                   (when (right-press? e start-sel)
                     (.preventDefault e)
                     (.stopPropagation e)
                     (.setPointerCapture pane (.-pointerId e))
                     (start (.-clientX e) (.-clientY e))))
   "pointermove" (fn [^js e] (when (.-current live) (add (.-clientX e) (.-clientY e))))
   "pointerup" (fn [^js e] (when (and (.-current live) (= 2 (.-button e))) (.stopPropagation e) (end)))
   "pointercancel" (fn [_] (cancel))
   ;; A press that started a line owns its menu event: with the pointer captured, Chrome sends it to
   ;; the pane itself (outside the chat's start zone), on press (Linux) or release (macOS, Windows).
   "contextmenu" (fn [^js e]
                   (when (or (.-current armed)
                             (not (or (.-shiftKey e) (in-text-field? e) (not (.closest (.-target e) start-sel)))))
                     (set! (.-current armed) false)
                     (.preventDefault e)))})

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
  "Installs the listeners of surface's pane; returns what removes them."
  [^js stage surface live handlers]
  (let [{:keys [start] pane-sel :pane} (surfaces surface)
        pane (or (.closest stage pane-sel) stage)
        ls (pane-listeners pane start live #js {:current false} handlers)
        on-msg (frame-listener pane handlers)]
    (doseq [[t f] ls] (.addEventListener pane t f true))
    (js/window.addEventListener "message" on-msg)
    #(do (doseq [[t f] ls] (.removeEventListener pane t f true))
         (js/window.removeEventListener "message" on-msg))))

;; ---- the chalk ----

(defn- line [pts cls]
  [:path {:class cls :d (circle/path-d pts)}])

(defn- layer-view [surface draft-key]
  (let [svg (h/use-ref nil)
        live (h/use-ref nil)
        dk (h/use-ref draft-key)
        [drawing set-line] (h/use-state nil)
        [pending set-pending] (h/use-state [])
        kept (circle/lines-on @(rf/subscribe [:attach draft-key]) surface)]
    (set! (.-current dk) draft-key)
    (h/use-effect
     (fn []
       (let [stage (.-parentElement ^js (.-current svg))]
         (listen! stage surface live (pointer stage surface dk live set-line set-pending))))
     [])
    [:svg.circle-layer {:ref #(set! (.-current svg) %) :aria-hidden true}
     (for [[i pts] (map-indexed vector (concat kept pending))] ^{:key i} [line pts "kept"])
     (when drawing [line drawing "drawing"])]))

(defn layer
  "The chalk over surface (\"picture\" or \"chat\"): the line being drawn and the draft's circles
  drawn there. Not on a phone (no right button) nor on another profile's chats (read only)."
  [surface draft-key]
  (when-not (or (db/phone?) @(rf/subscribe [:read-only?]))
    ^{:key (str surface draft-key)} [:f> layer-view surface draft-key]))
