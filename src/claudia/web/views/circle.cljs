(ns claudia.web.views.circle
  "Circling over the whole window: a right click starts a chalk line, the pointer draws it, a left
  click ends it (and does nothing else). The circled zone becomes an image in the draft (claudia.web.capture takes it
  from the shared tab), with what lies under the chalk; the line is wiped once the image is in the
  draft. Shift + right click opens the browser's menu, and so does a right click
  in a text field. Escape drops the line. While a
  line is drawn the freeform frames let the pointer through (body.chalking), and a frame forwards
  its right press (claudia.web.views.frame's bridge), since its page swallows it."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.capture :as capture]
            [claudia.web.circle :as circle]
            [claudia.web.db :as db]))

;; A right click here keeps its own menu: the browser's (paste, spelling) in a text field, Claudia's
;; on a chat's circle in the rail or its row in the list.
(def ^:private own-menu "input, textarea, select, [contenteditable], .rail-chat, .chat-row")

(defn- box-of [^js el]
  (let [r (.getBoundingClientRect el)]
    {:x (.-left r) :y (.-top r) :w (.-width r) :h (.-height r)}))

(defn- window-box []
  {:x 0 :y 0 :w js/window.innerWidth :h js/window.innerHeight})

(defn- zone
  "The window box of a line, padded, cut to the window."
  [points]
  (circle/clip (circle/bbox points) circle/pad (window-box)))

(defn- block-of [^js el]
  (when-let [^js b (some-> el (.closest "[data-block]"))]
    (let [d (.-dataset b)]
      {:id (.-block d) :name (.-name d) :kind (.-kind d) :form (not-empty (.-form d))})))

(defn- text-of
  "An element's text as shown (line breaks where the layout breaks); SVG text has only textContent."
  [^js el]
  (or (.-innerText el) (.-textContent el)))

(defn- under
  "What lies under the circle: the blocks it touches and the texts there (the outermost of each)."
  [z]
  (let [els (distinct (mapcat (fn [[x y]] (array-seq (js/document.elementsFromPoint x y))) (circle/grid z 9)))]
    {:blocks (vec (distinct (keep block-of els)))
     :texts (circle/outermost (keep #(circle/label-text (text-of %)) els))}))

(defn- surface-of
  "Where a zone lies: the chat (.left), the picture (.right) or both."
  [z]
  (circle/surface z (some-> (js/document.querySelector ".left") box-of) (some-> (js/document.querySelector ".right") box-of)))

(defn- failed [^js e]
  (if (= "NotAllowedError" (.-name e))
    "Circling needs this tab shared: allow it in the browser's prompt, then circle again."
    (str "The circle could not be taken: " (.-message e))))

(defn- take!
  "A finished line: its image goes to the draft (draft-key), then the line leaves pending."
  [draft-key points done]
  (let [z (or (zone points) (circle/bbox points))
        info (assoc (under z) :points points :surface (surface-of z))]
    (-> (capture/shot #(zone points))
        (.then (fn [file] (rf/dispatch [:ops/attach draft-key #js [file] {:circle info}])))
        (.catch (fn [e] (rf/dispatch [:ops/error (failed e)])))
        (.finally done))))

;; ---- the pointer ----

(defn- chalking! [on?]
  (.toggle (.-classList js/document.body) "chalking" on?))

(defn- on-own-menu? [^js e]
  (boolean (some-> (.-target e) (.closest own-menu))))

(defn- line-ops
  "What the line does: start at a point, add a point, finish (take it when drawn), drop it. live: a
  ref of the points now drawn; set-line and set-pending redraw; dk: a ref of the draft's key."
  [live dk set-line set-pending]
  (let [put! (fn [pts] (set! (.-current live) pts) (set-line pts) (chalking! (some? pts)))]
    {:start (fn [x y] (put! [[x y]]))
     :add (fn [x y] (when-let [pts (.-current live)] (put! (conj pts [x y]))))
     :cancel (fn [] (put! nil))
     :finish (fn []
               (when-let [pts (.-current live)]
                 (put! nil)
                 (when (circle/drawn? pts)
                   (set-pending #(conj % pts))
                   (take! (.-current dk) pts (fn [] (set-pending (fn [ps] (vec (remove #{pts} ps)))))))))}))

(defn- begin!
  "A right press at (x, y) starts a line, unless one is being drawn already or it is on something
  with a menu of its own (own-menu?). Returns whether the press was ours (its menu is cancelled): a right press
  while drawing is ours too, and changes nothing."
  [live {:keys [start]} x y own-menu?]
  (cond (.-current live) true
        own-menu? false
        :else (do (start x y) true)))

(defn- listeners
  "The window's listeners (capture phase: a 3D scene never sees the press). armed: a ref, true from
  a right press that was ours until its menu event; swallow: a ref, true from the left press that
  ended a line until its click, which then does nothing else."
  [live armed swallow {:keys [add cancel finish] :as ops}]
  {"pointerdown" (fn [^js e]
                   (case (.-button e)
                     2 (when (= "mouse" (.-pointerType e))
                         (let [mine? (and (not (.-shiftKey e))
                                          (begin! live ops (.-clientX e) (.-clientY e) (on-own-menu? e)))]
                           (set! (.-current armed) mine?)
                           (when mine? (.preventDefault e) (.stopPropagation e))))
                     0 (when (.-current live)
                         (set! (.-current swallow) true)
                         (finish)
                         (.preventDefault e)
                         (.stopPropagation e))
                     nil))
   "click" (fn [^js e]
             (when (.-current swallow)
               (set! (.-current swallow) false)
               (.preventDefault e)
               (.stopPropagation e)))
   "pointermove" (fn [^js e] (add (.-clientX e) (.-clientY e)))
   "keydown" (fn [^js e] (when (= "Escape" (.-key e)) (cancel)))
   "contextmenu" (fn [^js e]
                   (when (.-current armed) (set! (.-current armed) false) (.preventDefault e)))})

(defn- frame-box
  "The window box of the frame whose window is src, or nil."
  [src]
  (some #(when (identical? (.-contentWindow ^js %) src) (box-of %))
        (array-seq (js/document.querySelectorAll "iframe"))))

(defn- frame-listener
  "A freeform block's page forwards its right press ({claudiaChalk: down, x, y}); its frame lets
  the rest through once a line is drawn."
  [live ops]
  (fn [^js e]
    (when (= "down" (some-> (.-data e) .-claudiaChalk))
      (when-let [fb (frame-box (.-source e))]
        (let [{:keys [x y]} (circle/from-frame {:kind "down" :x (.. e -data -x) :y (.. e -data -y)} fb)]
          (begin! live ops x y false))))))

(defn- listen!
  "Installs the listeners; returns what removes them."
  [live ops]
  (let [armed #js {:current false}
        ls (listeners live armed #js {:current false} ops)
        on-msg (frame-listener live ops)]
    (doseq [[t f] ls] (js/window.addEventListener t f true))
    (js/window.addEventListener "message" on-msg)
    #(do (doseq [[t f] ls] (js/window.removeEventListener t f true))
         (js/window.removeEventListener "message" on-msg)
         (chalking! false))))

;; ---- the chalk ----

(defn- line [pts cls]
  [:path {:class cls :d (circle/path-d pts)}])

(defn- layer-view [draft-key]
  (let [live (h/use-ref nil)
        dk (h/use-ref draft-key)
        [drawing set-line] (h/use-state nil)
        [pending set-pending] (h/use-state [])]
    (set! (.-current dk) draft-key)
    (h/use-effect (fn [] (listen! live (line-ops live dk set-line set-pending))) [])
    [:svg.circle-layer {:aria-hidden true}
     (for [[i pts] (map-indexed vector pending)] ^{:key i} [line pts "kept"])
     (when drawing [line drawing "drawing"])]))

(defn layer
  "The chalk over the whole window: the line being drawn, and lines whose image is being taken. Not on a phone (no
  right button) nor on another profile's chats (read only)."
  [draft-key]
  (when-not (or (db/phone?) @(rf/subscribe [:read-only?]))
    [:f> layer-view draft-key]))
