(ns claudia.web.views.rightbar
  "The right half's bars, always shown: Picture | Timeline at the top right; under the picture a
  scrubber over every picture of the chat with ‹ ›, Back to live while an earlier picture is shown,
  and the snapshot camera. The fold has no button: Alt+P (its edge unfolds it)."
  (:require [re-frame.core :as rf]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.shell :as pure]
            [reagent.hooks :as h]))

(defn- clock [t]
  (when (and t (pos? t))
    (.toLocaleTimeString (js/Date. t) #js [] #js {:hour "2-digit" :minute "2-digit" :hour12 false})))

(defn- view-switch [view picture-behind?]
  [:div.view-switch {:role "tablist"}
   [:button {:role "tab" :aria-selected (= view :picture) :class (when (= view :picture) "on")
             :title (if picture-behind? "New picture · show (Alt+T)" "Claude's picture (Alt+T)")
             :on-click #(rf/dispatch [:right/view :picture])}
    "Picture" (when picture-behind? [:span.fold-dot])]
   [:button {:role "tab" :aria-selected (= view :timeline) :class (when (= view :timeline) "on")
             :title "Every step of the chat and its agents (Alt+T)"
             :on-click #(rf/dispatch [:right/view :timeline])}
    "Timeline"]])

(defn- use-tip-place!
  "The hover label sits over its mark (at, in percent of the track) but never leaves the right half."
  [track tip at hover]
  (h/use-layout-effect
   (fn []
     (when-let [t (.-current tip)]
       (let [el (.-current track)
             box (.getBoundingClientRect (or (.closest el ".right") js/document.body))
             rect (.getBoundingClientRect el)
             w (.-offsetWidth t)
             x (- (+ (.-left rect) (/ (* (.-width rect) (at hover)) 100)) (/ w 2))
             ;; The tip is fixed: placed in the page's layer (the body, moved with the keyboard).
             l (.getBoundingClientRect js/document.body)]
         (set! (.. t -style -left) (str (- (pure/tip-left x w (.-left box) (.-right box)) (.-left l)) "px"))
         (set! (.. t -style -top) (str (- (.-top rect) (.-offsetHeight t) 6 (.-top l)) "px"))))
     js/undefined)))

(defn- track-events
  "Click or drag on the track picks the nearest picture; hovering marks it."
  [nearest index set-hover]
  {:on-pointer-down (fn [^js e]
                      (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                      (rf/dispatch [:picture/pick (nearest (.-clientX e))]))
   :on-pointer-move (fn [^js e]
                      (let [i (nearest (.-clientX e))]
                        (set-hover i)
                        (when (and (.hasPointerCapture (.-currentTarget e) (.-pointerId e)) (not= i index))
                          (rf/dispatch [:picture/pick i]))))
   :on-pointer-leave #(set-hover nil)})

(defn- scrubber
  "One mark per picture along a track; click or drag to move through them, hover to see which one."
  [pictures index]
  (let [track (h/use-ref nil)
        tip (h/use-ref nil)
        [hover set-hover] (h/use-state nil)
        width (hooks/use-width track [])
        n (count pictures)
        at #(pure/mark-at n %)
        nearest (fn [x]
                  (let [rect (.getBoundingClientRect (.-current track))]
                    (pure/nearest-mark n (.-left rect) (.-width rect) x)))]
    (use-tip-place! track tip at hover)
    [:div.rbar-track (merge {:class (pure/density n width) :ref #(set! (.-current track) %)
                             :role "slider" :aria-label "Pictures" :aria-valuemin 1 :aria-valuemax n :aria-valuenow (inc index)}
                            (track-events nearest index set-hover))
     [:span.rbar-rail]
     [:span.rbar-fill {:style {:width (str (at index) "%")}}]
     (for [[i p] (map-indexed vector pictures)]
       ^{:key (:turn p)}
       [:span.rbar-tick {:class [(when (= i index) "on") (when (= i hover) "hover")]
                         :style {:left (str (at i) "%")}}])
     (when-let [p (and hover (get pictures hover))]
       [:span.rbar-tip {:ref #(set! (.-current tip) %)}
        [:b (inc hover)] " " (if (seq (:title p)) (:title p) "Untitled") " " [:span.muted (clock (:at p))]])]))

(defn- stepper [{:keys [pictures index viewing? loading?]}]
  (let [n (count pictures)]
    [:div.rbar-nav
     [:button.rbar-step {:disabled (<= index 0) :title "Previous picture (←)" :aria-label "Previous picture"
                         :on-click #(rf/dispatch [:picture/step -1])} "‹"]
     (if (> n 1) [:f> scrubber pictures index] [:span.rbar-track-empty])
     [:button.rbar-step {:disabled (>= index (dec n)) :title "Next picture (→)" :aria-label "Next picture"
                         :on-click #(rf/dispatch [:picture/step 1])} "›"]
     (when viewing?
       [:button.rbar-chip.past {:on-click #(rf/dispatch [:picture/live]) :title "Back to the live picture"}
        [:span.rbar-chip-dot] [:span.rbar-chip-text (if loading? "Loading…" "Back to live")]])]))

(defn top
  "The row over the right half: Picture | Timeline at its right."
  []
  (let [view @(rf/subscribe [:right-view])
        {:keys [picture-behind?]} @(rf/subscribe [:fold-state])]
    [:div.rtop [view-switch view picture-behind?]]))

(defn bar
  "The bar under the picture; extra: more buttons at its end (the snapshot camera)."
  [extra]
  [:footer.rbar-zone
   [:div.rbar
    [stepper @(rf/subscribe [:picture-history])]
    extra]])

(defn fold-edge
  "The folded right half: a thin chalk edge; it glows when a new picture came meanwhile."
  [unseen?]
  [:section.right.fold-edge
   [:button.unfold {:class (when unseen? "unseen") :on-click #(rf/dispatch [:right/fold])
                    :title (if unseen? "New picture · show (Alt+P)" "Show picture (Alt+P)") :aria-label "Show picture"}
    [:span.edge-line]]])
