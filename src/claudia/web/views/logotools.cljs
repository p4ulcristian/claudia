(ns claudia.web.views.logotools
  "The switch by Claudia's face: 2D (the chats) or 3D (the world: the chats as robots, Alt+W). The
  sounds switch moved to the chat's header (claudia.web.views.head). Shown under the face on the rail, in the open chat list's header and
  in the world's bar, so the way back from the world is the same switch."
  (:require [re-frame.core :as rf]))

(defn- dim-button [label on? world? tip]
  [:button {:class (when on? "on") :aria-pressed on? :data-tip tip
            :on-click #(rf/dispatch [:world/toggle world?])}
   label])

(defn tools []
  (let [world? @(rf/subscribe [:world?])]
    [:div.logo-tools
     [:div.lt-dim {:role "group" :aria-label "Chats in 2D or 3D"}
      (dim-button "2D" (not world?) false "The chats (Alt+W)")
      (dim-button "3D" world? true "World: your chats as robots (Alt+W)")]]))
