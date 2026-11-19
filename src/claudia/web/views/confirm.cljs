(ns claudia.web.views.confirm
  "The one confirm card: a chalk card over the window that asks before something goes (a form, a
  template, a chat into the archive). Escape, a click outside or the first button keeps it."
  (:require [re-frame.core :as rf]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.root-pass :as root-pass]))

(defn- card-view [{:keys [title yes on-yes on-no yes-disabled]} body]
  (hooks/use-key (fn [e] (when (= "Escape" (.-key e)) (.stopImmediatePropagation e) (on-no))) true [on-yes on-no])
  [:div.confirm-backdrop {:on-click (fn [e] (.stopPropagation e) (on-no))}
   [:div.confirm-card {:role "alertdialog" :aria-modal true :aria-label title :on-click #(.stopPropagation %)}
    [:h3 title]
    body
    [:div.confirm-actions
     [:button.confirm-no {:type "button" :auto-focus true :on-click on-no} "Keep it"]
     [:button.confirm-yes {:type "button" :disabled yes-disabled :on-click on-yes} yes]]]])

(defn card
  "Asks before something goes: title (the question), yes (the button that does it), on-yes, on-no,
  yes-disabled, body (what it means, a [:p …])."
  [opts body]
  [:f> card-view opts body])

(defn- root-card
  "The root switch's confirm card: yes opens the passkey (README decision 79)."
  [k title st]
  (let [{:keys [prepared busy error]} @st]
    [card {:title "Give this chat root?" :yes (if busy "Waiting for the passkey…" "Turn root on with passkey")
           :yes-disabled (not (root-pass/ready? @st))
           :on-yes #(root-pass/tap! st k "on" nil (fn [grant] (rf/dispatch [:root/confirm grant])))
           :on-no #(rf/dispatch [:root/keep])}
     [:<>
      [:p [:b (if (seq title) title "This chat")] " runs every sudo command as root without asking you, on the whole server, until you turn root off."]
      (cond error [:p.confirm-error error]
            (nil? prepared) [:p.muted "Getting the passkey ready…"])]]))

(defn root-ask
  "Asked before a chat gets root, with the owner's passkey (turning it off asks nothing)."
  []
  (when-let [{:keys [k title]} @(rf/subscribe [:root-ask])]
    ^{:key (str k)} [root-pass/holder k "on" nil (fn [st] [root-card k title st])]))

(defn archive-ask
  "Asked before a chat goes into the archive (unarchiving asks nothing)."
  []
  (when-let [{:keys [title]} @(rf/subscribe [:archive-ask])]
    [card {:title "Archive this chat?" :yes "Archive"
           :on-yes #(rf/dispatch [:archive/confirm]) :on-no #(rf/dispatch [:archive/keep])}
     [:p [:b (if (seq title) title "This chat")] " stops now: its running turn, its background work and its Claude Code. It leaves the list and stays under Archived; writing to it brings it back."]]))
