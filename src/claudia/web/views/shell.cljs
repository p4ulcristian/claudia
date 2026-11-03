(ns claudia.web.views.shell
  "The layout. Desktop: chat list (foldable) | chat column | picture. Phone (under 680 px): three
  full screens, list -> chat -> picture, one shown at a time."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.admin :as admin]
            [claudia.web.views.attach :as attach]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.codeforms :as codeforms]
            [claudia.web.views.rail :as rail]
            [claudia.web.views.settings :as settings]
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
        open @(rf/subscribe [:open])
        pending @(rf/subscribe [:pending-new])
        ro @(rf/subscribe [:read-only?])
        draft-key (if (and open (not (contains? pending (second open)))) open :new)
        folded? (:folded? @(rf/subscribe [:fold-state]))]
    (chat-colour! color)
    [:div.app {:class (when-not sidebar? "folded") :data-screen (name screen)}
     [:button.menu-toggle {:title (if sidebar? "Hide chats" "Show chats")
                           :on-click #(rf/dispatch [:sidebar/toggle])}
      [:svg.burger {:viewBox "0 0 20 20"} [:path {:d "M3 5.5h14M3 10h14M3 14.5h14"}]]]
     [:div.sidebar-slot
      ;; Folded on a desktop: the rail of chat circles in the list's place.
      (if sidebar? [sidebar/sidebar] [:<> [:div.phone-only.side-phone [sidebar/sidebar]] [rail/rail]])]
     [chatmenu/chat-menu]
     [settings/github-prompt]
     [:f> attach/drop-zone draft-key (not ro)]
     [:main.split {:class (when folded? "folded")}
      [chat/chat-pane]
      [picture/picture-pane]]
     ;; The right rail (desktop): this chat's forms, beside the picture.
     [picture/right-rail]
     [admin/dialog]
     [codeforms/gallery-host]]))
