(ns claudia.web.views.chat-groups
  "A group in the chat list (README decision 38): its header (fold, name, how many, + for a new chat
  in it with the group's picks) and its chats under it; the name is typed in place when the group is
  new or its name is double-clicked. Each chat row and header carries what a drag needs
  (claudia.web.views.chat-drag)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.shared.order :as order]
            [claudia.web.views.chat-drag :as drag]))

(defn- rename! [gid text]
  (let [n (str/trim (str text))]
    (rf/dispatch [:order/change #(order/update-group % gid (fn [g] (assoc g :name (if (seq n) n "Group"))))])
    (rf/dispatch [:order/renaming nil])))

(defn name-input
  "The group's name typed in place (Enter, Escape or leaving it keeps it; empty: \"Group\"); the list's
  header and the rail's chooser card use it."
  [gid current]
  (let [text (r/atom (if (= current "New group") "" current))]
    (fn [_ _]
      [:input.group-name-input {:value @text :auto-focus true :placeholder "Group name" :spell-check false
                                :on-change #(reset! text (.. % -target -value))
                                :on-blur #(rename! gid @text)
                                :on-key-down #(when (#{"Enter" "Escape"} (.-key %)) (.blur (.-target %)))}])))

(def ungroup-icon
  "Ungroup, drawn: a box opening, two arrows leaving it."
  [:svg {:viewBox "0 0 24 24" :width 16 :height 16 :aria-hidden true :fill "none" :stroke "currentColor"
         :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M4 9V5h16v4M4 15v4h16v-4M9 12H3m3-3-3 3 3 3M15 12h6m-3-3 3 3-3 3"}]])

(defn ungroup-button
  "The icon that ungroups group gid: its chats stay where it was, in its order."
  [gid group-name on-done]
  [:button.group-ungroup {:type "button" :title (str "Ungroup " group-name) :aria-label (str "Ungroup " group-name)
                          :on-click #(do (rf/dispatch [:order/change (fn [items] (order/ungroup items gid))]) (on-done))}
   ungroup-icon])

(defn- head [{gid :group group-name :name folded :folded} n]
  [:div.group-head {:data-group gid :class (when folded "folded")}
   [:button.group-fold {:type "button" :aria-label (if folded "Open the group" "Fold the group") :aria-expanded (not folded)
                        :on-click #(rf/dispatch [:order/change (fn [items] (order/update-group items gid (fn [g] (update g :folded not))))])}
    (if folded "▸" "▾")]
   (if (= gid @(rf/subscribe [:renaming-group]))
     [name-input gid group-name]
     [:span.group-name {:on-double-click #(rf/dispatch [:order/renaming gid])} group-name])
   [:span.group-count n]
   [ungroup-button gid group-name identity]
   [:button.group-add {:type "button" :title (str "New chat in " group-name) :aria-label (str "New chat in " group-name)
                       :on-click #(rf/dispatch [:order/new-in-group gid])} "+"]])

(defn item
  "A chat row as the list's order needs it: dragging and where it sits (top level or in group gid)."
  [cref gid row-view]
  [:div.order-item (cond-> {:data-ref cref :on-pointer-down #(drag/press! % cref)}
                     gid (assoc :data-in-group gid)
                     (not gid) (assoc :data-top true))
   row-view])

(defn group
  "A group: its header, then its chats (draw-row: row -> the row's view) unless folded."
  [{g :group rows :rows} draw-row]
  [:div.chat-group {:data-top true :data-key (:group g)}
   [head g (count rows)]
   (when-not (:folded g)
     (for [rw rows :let [cref (order/ref-of rw)]]
       ^{:key cref} [item cref (:group g) (draw-row rw)]))])
