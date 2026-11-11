(ns claudia.web.views.logotools
  "The two switches by Claudia's face: the sounds on or off, and 2D (the chats) or 3D (the world:
  the chats as robots, Alt+W). Shown under the face on the rail, in the open chat list's header and
  in the world's bar, so the way back from the world is the same switch."
  (:require [re-frame.core :as rf]
            [claudia.web.alerts :as alerts]))

(defn- speaker [on?]
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:path {:d "M3 8h3l4-3.5v11L6 12H3z"}]
   (if on?
     [:path {:d "M13 7.5c1 1.4 1 3.6 0 5M15.4 5.5c2 2.5 2 6.5 0 9"}]
     [:path {:d "M13.5 8l4 4M17.5 8l-4 4"}])])

(defn- sound-button [sounds?]
  [:button.lt-sound {:class (when sounds? "on")
                     :data-tip (if sounds? "Sounds on: click to mute" "Sounds off: click to hear when a chat finishes or waits")
                     :aria-label "Sounds" :aria-pressed (boolean sounds?)
                     :on-click #(alerts/set-sounds! (not sounds?))}
   [speaker sounds?]])

(defn- dim-button [label on? world? tip]
  [:button {:class (when on? "on") :aria-pressed on? :data-tip tip
            :on-click #(rf/dispatch [:world/toggle world?])}
   label])

(defn tools []
  (let [world? @(rf/subscribe [:world?])]
    [:div.logo-tools
     (sound-button (:sounds @alerts/on))
     [:div.lt-dim {:role "group" :aria-label "Chats in 2D or 3D"}
      (dim-button "2D" (not world?) false "The chats (Alt+W)")
      (dim-button "3D" world? true "World: your chats as robots (Alt+W)")]]))
