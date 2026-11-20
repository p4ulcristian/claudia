(ns claudia.web.views.chat-drag
  "Dragging chats in the list and on the rail (README decisions 38 and 80): a finger holds a chat for
  350 ms (a mouse just moves it 6 px) and it lifts; it follows the pointer, and where it is let go
  decides: on the upper or lower third of a chat, before or after it (inside its group when it is in
  one); on the middle of a chat, the two become a group (a chat already in a group: after it, in that
  group); on a group (the list's header, the rail's stack), into that group (its edges: before or
  after the group). The change goes to claudia.web.order. A chat that may tear out (the rail's, with
  a mouse) and is let go outside its home ([data-drag-home], the rail) opens in a window of its own
  (claudia.web.views.tearoff). While a finger drags, the list does not scroll."
  (:require [re-frame.core :as rf]
            [claudia.shared.order :as order]))

(def ^:private hold-ms 350)
(def ^:private move-px 6)
(def ^:private home-sel "[data-drag-home]")

;; {:cref :row :x0 :y0 :touch? :tear :started :moved :timer :ghost :mark [el class] :drop target :out?}
(defonce ^:private drag (atom nil))

(defn dragging? "Whether a chat is lifted now." [] (boolean (:started @drag)))

;; ---- pure ----

(defn zone-of
  "Where a fraction f (0 top .. 1 bottom) of an element's height falls: :before (top third), :after
  (bottom third), else :onto."
  [f]
  (cond (< f 0.3) :before (> f 0.7) :after :else :onto))

(defn target-for
  "What letting chat cref go does over an element whose facts are ref (a chat's), group (a group's
  id), in (the group the chat is in), zone and after (the key after it at its level): [:place
  before-key] [:into gid before-ref] [:group onto-ref], or nil (over the dragged chat itself)."
  [{:keys [ref group in zone after]} cref]
  (cond
    (and ref (= ref cref)) nil
    group (case zone :before [:place group] :after [:place after] [:into group nil])
    in (if (= zone :before) [:into in ref] [:into in after])
    (= zone :onto) [:group ref]
    :else (if (= zone :before) [:place ref] [:place after])))

(defn change-for
  "The change to the list (items -> items) for letting cref go on target (gid: a new group's id)."
  [cref [kind a b] gid]
  (case kind
    :place #(order/place % cref a)
    :into #(order/into-group % cref a b)
    :group #(order/group-with % cref a gid "New group")
    identity))

(defn mark-class
  "The class that shows where a drop lands: onto a chat (a group), into a group, or a line before or
  after the element (z: its zone)."
  [[kind] z on-group?]
  (cond (= kind :group) "drop-onto"
        (and on-group? (= kind :into)) "drop-into"
        (= z :before) "drop-before"
        :else "drop-after"))

(defn tears?
  "Whether letting go tears the chat out: it may (tear?), a mouse drags it (not touch?) and the
  pointer is outside its home (home? false; outside the window there is no element, so no home)."
  [{:keys [tear? touch? home?]}]
  (boolean (and tear? (not touch?) (not home?))))

;; ---- the page ----

(defn- next-key
  "The key (data-ref or data-key) of the next element at the same level as el, or nil."
  [^js el level-sel]
  (loop [^js n (.-nextElementSibling el)]
    (cond (nil? n) nil
          (.matches n level-sel) (or (.. n -dataset -ref) (.. n -dataset -key))
          :else (recur (.-nextElementSibling n)))))

(defn- facts-of
  "What target-for needs of element el (a chat or a group) with the pointer at y."
  [^js el y]
  (let [d (.-dataset el) r (.getBoundingClientRect el) in (.-inGroup d)]
    {:ref (.-ref d) :group (.-group d) :in in
     :zone (zone-of (/ (- y (.-top r)) (max 1 (.-height r))))
     :after (cond (.-group d) (next-key (.-parentElement el) "[data-top]")
                  in (next-key el (str "[data-in-group='" in "']"))
                  :else (next-key el "[data-top]"))}))

(defn- clear-mark! []
  (when-let [[el cls] (:mark @drag)] (.remove (.-classList ^js el) cls))
  (swap! drag dissoc :mark :drop))

(defn- mark! [^js el t zone]
  (clear-mark!)
  (let [cls (mark-class t zone (some? (.. el -dataset -group)))]
    (.add (.-classList el) cls)
    (swap! drag assoc :mark [el cls] :drop t)))

(defn- show-out! [out?]
  (.toggle (.-classList js/document.body) "chat-tearing" out?)
  (swap! drag assoc :out? out?))

(defn- aim!
  "Marks where a drop at (x, y) lands, or that it tears out."
  [x y]
  (let [{:keys [cref tear touch?]} @drag
        ^js at (js/document.elementFromPoint x y)
        out? (tears? {:tear? (some? tear) :touch? touch? :home? (some? (some-> at (.closest home-sel)))})
        el (when-not out? (some-> at (.closest "[data-ref],[data-group]")))
        facts (some-> el (facts-of y))
        t (some-> facts (target-for cref))]
    (show-out! out?)
    (if t (mark! el t (:zone facts)) (clear-mark!))))

(defn- over! [^js e]
  (let [^js ghost (:ghost @drag) x (.-clientX e) y (.-clientY e)]
    (set! (.. ghost -style -transform) (str "translate(" (+ x 12) "px," (- y 18) "px)"))
    (aim! x y)))

(defn- lift!
  "The chat lifts: a copy of it (as wide as it is) follows the pointer, in its home so it looks the
  same, else in the page."
  [^js row]
  (let [^js ghost (.cloneNode row true)
        ^js home (or (.closest row home-sel) js/document.body)]
    (.add (.-classList ghost) "drag-ghost")
    (set! (.. ghost -style -width) (str (.-offsetWidth row) "px"))
    (.appendChild home ghost)
    (.add (.-classList js/document.body) "chat-dragging")
    (swap! drag assoc :started true :ghost ghost)))

(defn- drop!
  "What letting go does: the chat torn out, or the list changed (a new group asks for its name)."
  [{:keys [cref tear out?] target :drop}]
  (cond
    out? (tear)
    target (let [gid (str "g-" (random-uuid))]
             (rf/dispatch [:order/change (change-for cref target gid)])
             (when (= :group (first target)) (rf/dispatch [:order/renaming gid])))))

(defn- finish!
  "Ends a press; true when it was a real drag (lifted and moved), whose closing click must not open
  the chat. A finger held still past hold-ms and let go is a tap: the chat opens."
  []
  (let [{:keys [started moved ^js ghost timer out?] target :drop :as d} @drag]
    (some-> timer js/clearTimeout)
    (some-> ghost .remove)
    (clear-mark!)
    (.remove (.-classList js/document.body) "chat-dragging" "chat-tearing")
    (reset! drag nil)
    (when started (drop! d))
    (boolean (and started (or moved target out?)))))

(defn- on-move [^js e]
  (when-let [{:keys [started x0 y0 touch? timer row]} @drag]
    (let [far? (> (js/Math.hypot (- (.-clientX e) x0) (- (.-clientY e) y0)) move-px)]
      (cond
        started (do (when far? (swap! drag assoc :moved true)) (over! e))
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
  "pointerdown on a chat (cref its reference): a drag may begin (a finger holds still for hold-ms; a
  mouse moves move-px). tear: what tears the chat out when it is let go outside its home (nil: it
  never tears)."
  ([e cref] (press! e cref nil))
  ([^js e cref tear]
   (when (and (zero? (.-button e)) (nil? @drag))
     (let [row (.-currentTarget e) touch? (= "touch" (.-pointerType e))]
       (reset! drag {:cref cref :row row :x0 (.-clientX e) :y0 (.-clientY e) :touch? touch? :tear tear})
       (when touch?
         (swap! drag assoc :timer (js/setTimeout #(when (= cref (:cref @drag)) (lift! row)) hold-ms)))))))
