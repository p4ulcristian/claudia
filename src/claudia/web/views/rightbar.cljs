(ns claudia.web.views.rightbar
  "The bar over the bottom of the right half (shown while the mouse is near it, kept while an earlier
  picture is shown, always on a phone): Picture | Timeline, for pictures a scrubber over every
  picture of the chat with ‹ › and n/m, the live chip or Back to live, the snapshot camera, and the
  fold (Alt+P; on a phone: back to the chat)."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
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

(defn- scrubber
  "One mark per picture along a track; click or drag to move through them, hover to see which one."
  [pictures index]
  (let [track (h/use-ref nil)
        tip (h/use-ref nil)
        [hover set-hover] (h/use-state nil)
        [width set-width] (h/use-state 0)
        n (count pictures)
        at (fn [i] (if (= n 1) 100 (* 100 (/ i (dec n)))))
        nearest (fn [x]
                  (let [rect (.getBoundingClientRect (.-current track))
                        f (min 1 (max 0 (/ (- x (.-left rect)) (max 1 (.-width rect)))))]
                    (js/Math.round (* f (dec n)))))
        gap (if (> n 1) (/ width (dec n)) width)
        density (cond (>= gap 14) "" (>= gap 4) "dense" :else "packed")]
    (h/use-layout-effect
     (fn []
       (let [el (.-current track)
             ro (js/ResizeObserver. #(set-width (.-clientWidth el)))]
         (.observe ro el)
         #(.disconnect ro)))
     [])
    ;; The hover label sits over its mark but never leaves the right half.
    (h/use-layout-effect
     (fn []
       (when-let [t (.-current tip)]
         (let [el (.-current track)
               box (.getBoundingClientRect (or (.closest el ".right") js/document.body))
               rect (.getBoundingClientRect el)
               w (.-offsetWidth t)
               x (- (+ (.-left rect) (/ (* (.-width rect) (at hover)) 100)) (/ w 2))]
           (set! (.. t -style -left) (str (- (max (+ (.-left box) 8) (min x (- (.-right box) w 8))) (.-left rect)) "px"))))
       js/undefined))
    [:div.rbar-track {:class density :ref #(set! (.-current track) %)
                      :role "slider" :aria-label "Pictures" :aria-valuemin 1 :aria-valuemax n :aria-valuenow (inc index)
                      :on-pointer-down (fn [^js e]
                                         (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                         (rf/dispatch [:picture/pick (nearest (.-clientX e))]))
                      :on-pointer-move (fn [^js e]
                                         (let [i (nearest (.-clientX e))]
                                           (set-hover i)
                                           (when (and (.hasPointerCapture (.-currentTarget e) (.-pointerId e)) (not= i index))
                                             (rf/dispatch [:picture/pick i]))))
                      :on-pointer-leave #(set-hover nil)}
     [:span.rbar-rail]
     [:span.rbar-fill {:style {:width (str (at index) "%")}}]
     (for [[i p] (map-indexed vector pictures)]
       ^{:key (:turn p)}
       [:span.rbar-tick {:class [(when (= i index) "on") (when (= i hover) "hover")]
                         :style {:left (str (at i) "%")}}])
     (when-let [p (and hover (get pictures hover))]
       [:span.rbar-tip {:ref #(set! (.-current tip) %)}
        [:b (inc hover)] " " (if (seq (:title p)) (:title p) "Untitled") " " [:span.muted (clock (:at p))]])]))

(defn- stepper [{:keys [pictures index viewing? loading? unchanged?]}]
  (let [n (count pictures)]
    [:div.rbar-nav
     [:button.rbar-step {:disabled (<= index 0) :title "Previous picture (←)" :aria-label "Previous picture"
                         :on-click #(rf/dispatch [:picture/step -1])} "‹"]
     (if (> n 1) [:f> scrubber pictures index] [:span.rbar-track-empty])
     [:button.rbar-step {:disabled (>= index (dec n)) :title "Next picture (→)" :aria-label "Next picture"
                         :on-click #(rf/dispatch [:picture/step 1])} "›"]
     [:span.rbar-count (inc index) [:span.muted "/" n]]
     (if viewing?
       [:button.rbar-chip.past {:on-click #(rf/dispatch [:picture/live]) :title "Back to the live picture"}
        [:span.rbar-chip-dot] [:span.rbar-chip-text (if loading? "Loading…" "Back to live")]]
       [:span.rbar-chip.live {:class (when unchanged? "still")
                              :title (if unchanged? "The live picture; the last turn didn't change it" "The live picture")}
        [:span.rbar-chip-dot] [:span.rbar-chip-text (if unchanged? "Live · unchanged" "Live")]])]))

(defn bar
  "extra: more buttons before the fold (the snapshot camera)."
  [extra]
  (let [view @(rf/subscribe [:right-view])
        hist @(rf/subscribe [:picture-history])
        {:keys [picture-behind? fold-disabled?]} @(rf/subscribe [:fold-state])]
    [:footer.rbar
     [view-switch view picture-behind?]
     (if (and (= view :picture) (seq (:pictures hist)))
       [stepper hist]
       [:span.grow])
     extra
     [:button.rbar-fold {:disabled fold-disabled?
                         :title "Hide the right half (Alt+P)" :aria-label "Hide the right half"
                         :on-click #(rf/dispatch [:right/fold])}
      "›"]]))

(defonce ^:private near (r/atom false))

(def mouse-props
  "For the right half's element: whether the mouse is within 96 px of its bottom (the bar shows)."
  {:on-mouse-move (fn [^js e]
                    (let [v (> (.-clientY e) (- (.-bottom (.getBoundingClientRect (.-currentTarget e))) 96))]
                      (when (not= v @near) (reset! near v))))
   :on-mouse-leave #(reset! near false)})

(defn zone [extra]
  (let [viewing? (:viewing? @(rf/subscribe [:picture-history]))]
    [:div.rbar-zone {:class [(when @near "show") (when viewing? "pinned")]}
     [bar extra]]))

(defn fold-edge
  "The folded right half: a thin chalk edge; it glows when a new picture came meanwhile."
  [unseen?]
  [:section.right.fold-edge
   [:button.unfold {:class (when unseen? "unseen") :on-click #(rf/dispatch [:right/fold])
                    :title (if unseen? "New picture · show (Alt+P)" "Show picture (Alt+P)") :aria-label "Show picture"}
    [:span.edge-line]]])
