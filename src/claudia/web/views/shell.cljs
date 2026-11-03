(ns claudia.web.views.shell
  "The layout. Desktop: chat list (foldable) | chat column | picture. Phone (under 680 px): three
  full screens, list -> chat -> picture, one shown at a time."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.picture :as picture]
            [claudia.web.views.sidebar :as sidebar]))

(defn- chat-colour!
  "The open chat's colour tints the whole window (DESIGN.md: chat colours)."
  [color]
  (h/use-effect
   (fn []
     (let [root js/document.documentElement
           v (sidebar/chat-color-var color)]
       (if v
         (do (.setAttribute root "data-chat" color)
             (.setProperty (.-style root) "--hue" v))
         (do (.removeAttribute root "data-chat")
             (.removeProperty (.-style root) "--hue"))))
     nil)
   [color]))

(defn app []
  (let [screen @(rf/subscribe [:screen])
        sidebar? @(rf/subscribe [:sidebar?])
        color @(rf/subscribe [:open-color])
        folded? (:folded? @(rf/subscribe [:fold-state]))]
    (chat-colour! color)
    [:div.app {:class (when-not sidebar? "folded") :data-screen (name screen)}
     [:button.menu-toggle {:title (if sidebar? "Hide chats" "Show chats")
                           :on-click #(rf/dispatch [:sidebar/toggle])}
      [:svg.burger {:viewBox "0 0 20 20"} [:path {:d "M3 5.5h14M3 10h14M3 14.5h14"}]]]
     [:div.sidebar-slot [sidebar/sidebar]]
     [:main.split {:class (when folded? "folded")}
      [chat/chat-pane]
      [picture/picture-pane]]]))
