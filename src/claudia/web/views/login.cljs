(ns claudia.web.views.login
  "The cover over the whole app while the gate's login is gone (claudia.web.login): nothing under it
  works until the page signs in again, so it can't be put away; its one button reloads through the
  gate's sign-in."
  (:require [re-frame.core :as rf]))

(defn- card []
  [:div.login-card.confirm-card {:role "alertdialog" :aria-modal true :aria-label "You're logged out"}
   [:h3 "You're logged out"]
   [:p "Your login on this device ended, so Claudia refuses the mic, uploads and every other request until you log in again."]
   [:p "Your drafts and unsent voice clips stay on this device."]
   [:div.confirm-actions
    [:button.confirm-yes.login-yes {:type "button" :auto-focus true :on-click #(rf/dispatch [:login/sign-in])}
     "Log in again"]]])

(defn cover
  "Over everything while the login is gone; nothing otherwise."
  []
  (when @(rf/subscribe [:logged-out?])
    [:div.login-cover [card]]))
