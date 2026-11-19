(ns claudia.web.views.right-pick
  "The right half's dropdown (README decision 75): its top row names the view shown, and a press
  lists every view (claudia.shared.views) to switch to; the pick is the person's on every device
  (claudia.web.right). A read-only view of another person's chats has no File Browser (their
  runner answers no folder calls)."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.shared.views :as views]
            [claudia.web.hooks :as hooks]))

(def ^:private chev
  [:svg.rp-chev {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M5 7.5l5 5 5-5"}]])

(defn- choices
  "The views to pick from: all, without the File Browser in a read-only view."
  [ro]
  (remove (fn [[id]] (and ro (= id :files))) views/all))

(defn- item [v on? pick!]
  (let [[id label] v]
    [:button.rp-item {:type "button" :role "menuitemradio" :aria-checked on? :class (when on? "on")
                      :on-click #(pick! id)}
     label (when on? [:span.rp-tick "✓"])]))

(defn pick
  "The dropdown: shown names the view on screen; ro: a read-only view."
  [shown ro]
  (let [[open set-open] (h/use-state false)
        box (h/use-ref nil)
        pick! (fn [id] (set-open false) (rf/dispatch [:right/view id]))]
    (hooks/use-click-outside box open #(set-open false))
    (hooks/use-key (fn [^js e] (when (and open (= "Escape" (.-key e))) (.stopPropagation e) (set-open false))) true [open])
    [:div.rp-row
     [:div.rp {:ref #(set! (.-current box) %)}
      [:button.rp-button {:type "button" :aria-haspopup "menu" :aria-expanded open
                          :on-click #(set-open (not open))}
       (views/label shown) chev]
      (when open
        [:div.rp-pop {:role "menu" :aria-label "Show on the right"}
         (for [v (choices ro)] ^{:key (first v)} [item v (= (first v) shown) pick!])])]]))
