(ns claudia.web.views.confirm
  "The one confirm card: a chalk card over the window that asks before something goes (a form, a
  template, a chat into the archive). Escape, a click outside or the first button keeps it."
  (:require [re-frame.core :as rf]
            [claudia.web.hooks :as hooks]))

(defn- card-view [{:keys [title yes on-yes on-no]} body]
  (hooks/use-key (fn [e] (when (= "Escape" (.-key e)) (.stopImmediatePropagation e) (on-no))) true [on-yes on-no])
  [:div.confirm-backdrop {:on-click (fn [e] (.stopPropagation e) (on-no))}
   [:div.confirm-card {:role "alertdialog" :aria-modal true :aria-label title :on-click #(.stopPropagation %)}
    [:h3 title]
    body
    [:div.confirm-actions
     [:button.confirm-no {:type "button" :auto-focus true :on-click on-no} "Keep it"]
     [:button.confirm-yes {:type "button" :on-click on-yes} yes]]]])

(defn card
  "Asks before something goes: title (the question), yes (the button that does it), on-yes, on-no,
  body (what it means, a [:p …])."
  [opts body]
  [:f> card-view opts body])

(defn archive-ask
  "Asked before a chat goes into the archive (unarchiving asks nothing)."
  []
  (when-let [{:keys [title]} @(rf/subscribe [:archive-ask])]
    [card {:title "Archive this chat?" :yes "Archive"
           :on-yes #(rf/dispatch [:archive/confirm]) :on-no #(rf/dispatch [:archive/keep])}
     [:p [:b (if (seq title) title "This chat")] " leaves the list. It stays under Archived, and you can unarchive it any time."]]))
