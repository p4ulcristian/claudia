(ns claudia.web.views.chat-drag
  "Dragging chats in the list (README decision 38): a finger holds a row for 350 ms (a mouse just
  moves it 6 px) and the row lifts; it follows the pointer, and where it is let go decides: on the
  upper or lower third of a chat, before or after it (inside its group when it is in one); on the
  middle of a chat, the two become a group; on a group's header, into that group (its edges: before
  or after the group). The change goes to claudia.web.order. While a finger drags, the list does not
  scroll."
  (:require [re-frame.core :as rf]
            [claudia.shared.order :as order]))

(def ^:private hold-ms 350)
(def ^:private move-px 6)

;; {:cref :x0 :y0 :touch? :started :timer :ghost :mark [el class] :drop target}
(defonce ^:private drag (atom nil))

(defn- clear-mark! []
  (when-let [[el cls] (:mark @drag)] (.remove (.-classList ^js el) cls))
  (swap! drag dissoc :mark :drop))

(defn- zone
  "Where in an element's height y falls: :before (top third), :after (bottom third), else :onto."
  [^js el y]
  (let [r (.getBoundingClientRect el) f (/ (- y (.-top r)) (max 1 (.-height r)))]
    (cond (< f 0.3) :before (> f 0.7) :after :else :onto)))

(defn- next-key
  "The key (data-ref or data-group) of the next element at the same level as el, or nil."
  [^js el level-sel]
  (loop [^js n (.-nextElementSibling el)]
    (cond (nil? n) nil
          (.matches n level-sel) (or (.. n -dataset -ref) (.. n -dataset -key))
          :else (recur (.-nextElementSibling n)))))

(defn target-of
  "What letting go over element el (a chat row or a group header) at y does: [:place before-key]
  [:into gid before-ref] [:group onto-ref] or nil (over the dragged row itself)."
  [^js el y cref]
  (let [ref-at (.. el -dataset -ref) gid (.. el -dataset -group) in (.. el -dataset -inGroup) z (zone el y)]
    (cond
      (= ref-at cref) nil
      gid (case z :before [:place gid] :after [:place (next-key (.-parentElement el) "[data-top]")] [:into gid nil])
      (= z :onto) [:group ref-at]
      in (if (= z :before) [:into in ref-at] [:into in (next-key el (str "[data-in-group='" in "']"))])
      :else (if (= z :before) [:place ref-at] [:place (next-key el "[data-top]")]))))

(defn change-for
  "The change to the list (items -> items) for letting cref go on target (gid: a new group's id)."
  [cref [kind a b] gid]
  (case kind
    :place #(order/place % cref a)
    :into #(order/into-group % cref a b)
    :group #(order/group-with % cref a gid "New group")
    identity))

(defn mark-class
  "The class that shows where a drop lands: onto a chat (a group), into a group's header, or a line
  before or after the element (z: its zone)."
  [[kind] z on-head?]
  (cond (= kind :group) "drop-onto"
        (and on-head? (= kind :into)) "drop-into"
        (= z :before) "drop-before"
        :else "drop-after"))

(defn- mark! [^js el t y]
  (clear-mark!)
  (let [cls (mark-class t (zone el y) (some? (.. el -dataset -group)))]
    (.add (.-classList el) cls)
    (swap! drag assoc :mark [el cls])))

(defn- over! [^js e]
  (let [{:keys [cref ^js ghost]} @drag x (.-clientX e) y (.-clientY e)]
    (set! (.. ghost -style -transform) (str "translate(" (+ x 12) "px," (- y 18) "px)"))
    (if-let [el (some-> (js/document.elementFromPoint x y) (.closest "[data-ref],[data-group]"))]
      (if-let [t (target-of el y cref)] (do (mark! el t y) (swap! drag assoc :drop t)) (clear-mark!))
      (clear-mark!))))

(defn- lift! [^js row]
  (let [^js ghost (.cloneNode row true)]
    (.add (.-classList ghost) "drag-ghost")
    (.appendChild js/document.body ghost)
    (.add (.-classList js/document.body) "chat-dragging")
    (swap! drag assoc :started true :ghost ghost)))

(defn- finish! []
  (let [{:keys [cref started ^js ghost timer] target :drop} @drag]
    (some-> timer js/clearTimeout)
    (some-> ghost .remove)
    (clear-mark!)
    (.remove (.-classList js/document.body) "chat-dragging")
    (reset! drag nil)
    (when (and started target)
      (let [gid (str "g-" (random-uuid))]
        (rf/dispatch [:order/change (change-for cref target gid)])
        ;; A new group asks for its name at once.
        (when (= :group (first target)) (rf/dispatch [:order/renaming gid]))))
    started))

(defn- on-move [^js e]
  (when-let [{:keys [started x0 y0 touch? timer row]} @drag]
    (let [far? (> (js/Math.hypot (- (.-clientX e) x0) (- (.-clientY e) y0)) move-px)]
      (cond
        started (over! e)
        (and far? touch?) (do (js/clearTimeout timer) (reset! drag nil))   ; a scroll, not a drag
        far? (do (lift! row) (over! e))))))

(defn- on-up [^js e]
  (when @drag
    (when (finish!)
      ;; The click a drag ends with does not open the chat.
      (js/window.addEventListener "click" #(do (.stopPropagation %) (.preventDefault %)) #js {:capture true :once true}))
    (.preventDefault e)))

(defonce ^:private listening
  (when (exists? js/window)
    (js/window.addEventListener "pointermove" on-move)
    (js/window.addEventListener "pointerup" on-up)
    (js/window.addEventListener "pointercancel" #(when (and @drag (not (:started @drag))) (finish!)))
    ;; A finger dragging a chat does not scroll the list.
    (js/window.addEventListener "touchmove" #(when (:started @drag) (.preventDefault %)) #js {:passive false})))

(defn press!
  "pointerdown on a chat row (cref its reference): a drag may begin (a finger holds still for
  hold-ms; a mouse moves move-px)."
  [^js e cref]
  (when (and (zero? (.-button e)) (nil? @drag))
    (let [row (.-currentTarget e) touch? (= "touch" (.-pointerType e))]
      (reset! drag {:cref cref :row row :x0 (.-clientX e) :y0 (.-clientY e) :touch? touch?})
      (when touch?
        (swap! drag assoc :timer (js/setTimeout #(when (= cref (:cref @drag)) (lift! row)) hold-ms))))))
