(ns claudia.web.views.head-pill
  "The chat header's pill (README decisions 22, 90, 117): one look for every setting of the header
  and of the strip over the message box, an icon
  (or the chat's colour as a dot), the value and a ▾ when it can change; a click opens its popup
  under it, kept inside the header panel, closed by a pointer down outside or Escape."
  (:require [reagent.hooks :as h]
            [claudia.web.views.pure.words :as words]))

(defn- use-closes-away!
  "While open, a pointer down outside box or Escape calls close."
  [box open close]
  (h/use-effect
   (fn []
     (when open
       (let [away (fn [e] (when-not (some-> (.-current box) (.contains (.-target e))) (close)))
             esc (fn [e] (when (= "Escape" (.-key e)) (close)))]
         (js/window.addEventListener "pointerdown" away)
         (js/window.addEventListener "keydown" esc)
         #(do (js/window.removeEventListener "pointerdown" away)
              (js/window.removeEventListener "keydown" esc)))))
   [open]))

(defn- use-popup
  "A popup's state: [open? toggle! close! box-ref], closing on a pointer down outside or Escape."
  []
  (let [[open set-open] (h/use-state false)
        box (h/use-ref nil)
        close! #(set-open false)]
    (use-closes-away! box open close!)
    [open #(set-open not) close! box]))

(def bell-path "M6 9.5a6 6 0 0 1 12 0c0 4.6 1.8 6.2 1.8 6.2H4.2S6 14.1 6 9.5M10 19a2 2 0 0 0 4 0")

(def icons
  "The pills' icons in place of words: the computer (the server), the folder (the project without a
  logo; the rail's Files), a sparkle (model · effort), a speaker (the voice), the bell (notify) and
  the bell struck through (notify Off); the rail's fork and Sudo's shield."
  {:server "M3.5 5h17v11h-17zM8.5 20h7M12 16v4"
   :folder "M3 7.5V18a1.5 1.5 0 0 0 1.5 1.5h15A1.5 1.5 0 0 0 21 18V9a1.5 1.5 0 0 0-1.5-1.5h-7.7L9.5 5H4.5A1.5 1.5 0 0 0 3 6.5z"
   :model "M12 3l2.2 5.8L20 11l-5.8 2.2L12 19l-2.2-5.8L4 11l5.8-2.2z"
   :voice "M4 9.5h3.5L12 5.5v13l-4.5-4H4zM15.5 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11"
   :bell bell-path
   :bell-off (str bell-path "M4 4l16 16")
   :fork "M12 21v-6.5L6 8.5V4M12 14.5l6-6V4M3.5 6.5 6 4l2.5 2.5M15.5 6.5 18 4l2.5 2.5"
   :root "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6zM10 9.5h3a1.5 1.5 0 0 1 0 3h-3v-3M10 12.5V15M12.6 12.5 14 15"})

(defn icon
  "The 16 px icon named k (icons)."
  [k]
  [:svg.head-ico {:viewBox "0 0 24 24" :aria-hidden true :fill "none" :stroke "currentColor"
                  :stroke-width 1.7 :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d (icons k)}]])

(defn- colour-dot [c]
  [:span.colour-dot {:class (when (= c "ash") "passive") :style {:background (words/colour-fill c)}}])

(defn pill
  "One setting of row 2, every one the same: its icon (icons, named by k; dot: the chat's colour as
  a filled dot instead), the value and a ▾ when it can be changed; body: markup in place of the icon
  and the value (the strip's subscription pill, the where pill). label names it for the tip and
  screen readers; caret? false: no ▾ (the colour circle)."
  [{:keys [k dot body label value open on-click ro class caret?]}]
  [:button.head-pill {:type "button" :class class :disabled ro :data-tip label
                      :aria-label label :aria-haspopup "true" :aria-expanded (boolean open) :on-click on-click}
   (or body [:<> (if dot [colour-dot dot] [icon k]) [:span.pill-val value]])
   (when-not (or ro (false? caret?)) [:span.pill-caret "▾"])])

(defn- use-inside-panel!
  "While open, moves the popup in box (.head-pop) left so it ends inside the header panel or the
  strip over the message box (a pill near the right edge would push it out of the chat column)."
  [box open]
  (h/use-layout-effect
   (fn []
     (when-let [^js el (and open (some-> (.-current box) (.querySelector ".head-pop")))]
       (when-let [^js panel (.closest el ".head-panel, .chat-strip")]
         (let [over (- (.-right (.getBoundingClientRect el)) (.-right (.getBoundingClientRect panel)))]
           (when (pos? over) (set! (.. el -style -transform) (str "translateX(" (- over) "px)"))))))
     js/undefined)
   [open]))

(defn popped
  "A pill with its popup under it (opened by a click, closed by a pointer down outside or Escape),
  kept inside the header panel. opts: as pill; popup-of: (fn [close!]) the popup's markup."
  [opts popup-of]
  (let [[open toggle! close! box] (use-popup)]
    (use-inside-panel! box open)
    [:div.head-field {:ref #(set! (.-current box) %)}
     [pill (assoc opts :open open :on-click toggle!)]
     (when open (popup-of close!))]))

(defn pop-row
  "One choice of a popup list: the name bold, a quiet line under it (mono when mono?), ticked when on."
  [{:keys [title line mono? on? off? pick!]}]
  [:button.pop-row {:type "button" :role "menuitemradio" :aria-checked (boolean on?)
                    :class [(when on? "on") (when off? "off")] :on-click pick!}
   [:span.pop-row-name title]
   (when (seq line) [:span.pop-row-line {:class (when mono? "mono")} [:bdi line]])])
