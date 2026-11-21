(ns claudia.web.views.thumb-edge
  "The right edge of a touch tablet (README decisions 40, 73, 90): a big tablet held in two hands is
  held at its sides partway up, so the right thumb rests on the right edge at middle height. There a
  rail on the right wall, one column with the mic exactly at its middle height and four slots of one
  size over it and four under it: Search, Machines, Prompts, My words | mic | Stop, New chat,
  ‹ (the previous picture), › (the next picture). Each a button with its name under its icon, at
  least 12 mm, see-through at rest. A button never comes or goes, so nothing shifts and the mic never
  moves: one that cannot act now is passive (greyed and disabled), Stop is red while the open chat's
  turn runs (claudia.web.views.pure.edge). At its bottom, outside the eight, the foot the chats' rail
  had: the update pill, your profile circle (its menu holds Settings, Archived chats, Prompts, My
  words) and the version's line, so the chats have the left edge to themselves.
  The mic can be dragged out of the rail; its empty circle then offers it back
  (claudia.web.views.mic). The composer leaves its own mic out on these screens
  (claudia.web.views.chat)."
  (:require [claudia.web.search-open :as search-open]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.views.icons :as icons]
            [claudia.web.views.menu :as menu]
            [claudia.web.views.mic :as mic]
            [claudia.web.views.pure.edge :as pure]
            [claudia.web.views.systems :as systems]
            [claudia.web.views.update-pill :as update-pill]))

(defn- svg-of [& paths] (into [:svg {:viewBox "0 0 24 24" :aria-hidden true}] paths))

(def ^:private slots
  "Each slot: its name under the icon, its tip, its icon and what a press does."
  {:search {:label "Search" :tip "Search chats" :act search-open/open!
            :icon (svg-of [:circle {:cx 11 :cy 11 :r 6}] [:path {:d "M20 20l-4.5-4.5"}])}
   :machines {:label "Machines" :tip "Machines: CPU, memory, disks" :icon [systems/computer-icon]
              :act #(rf/dispatch [:right/view :machines])}
   :prompts {:label "Prompts" :tip "Prompt profiles" :icon icons/prompts-icon
             :act #(rf/dispatch [:right/view :prompts])}
   :words {:label "My words" :tip "My words" :icon icons/words-icon :act #(rf/dispatch [:right/view :words])}
   :stop {:label "Stop" :tip "Stop the running turn (Esc)" :act #(rf/dispatch [:chat/stop])
          :icon (svg-of [:rect {:x 6 :y 6 :width 12 :height 12 :rx 2}])}
   :new {:label "New chat" :tip "New chat (Alt+N)" :icon (svg-of [:path {:d "M12 5v14M5 12h14"}])
         :act #(rf/dispatch [:nav/new])}
   :prev {:label "Previous" :tip "Previous picture" :icon (svg-of [:path {:d "M14.5 5.5L8 12l6.5 6.5"}])
          :act #(rf/dispatch [:picture/step -1])}
   :next {:label "Next" :tip "Next picture" :icon (svg-of [:path {:d "M9.5 5.5L16 12l-6.5 6.5"}])
          :act #(rf/dispatch [:picture/step 1])}})

(defn- slot-button
  "One slot's button in its state (:shown, :passive or :red); passive, it is disabled."
  [id state]
  (let [{:keys [label tip icon act]} (slots id)]
    [:button.edge-step {:type "button" :class (name state) :disabled (= state :passive)
                        :aria-label tip :data-tip tip :on-click act}
     icon [:span.edge-name label]]))

(defn- composer-shown?
  "The composer shows (claudia.web.views.chat/pane-foot): not read-only, and a server to chat on."
  []
  (let [me @(rf/subscribe [:me])]
    (and (not @(rf/subscribe [:read-only?])) (seq (:servers me)))))

(defn- words-ready?
  "My words can act: where the composer shows and the hub has a listen service (claudia.web.words)."
  []
  (let [m @(rf/subscribe [:my-words])]
    (boolean (and (composer-shown?) (some? m) (not (:off m))))))

(defn- now
  "What the slots' states depend on (pure/slot-states), read from the page; oc: the open chat."
  [oc]
  (let [{:keys [pictures index]} @(rf/subscribe [:picture-history])]
    {:words? (words-ready?)
     :running? (boolean (when oc (:running @(rf/subscribe [:chat (:ck oc)]))))
     :new? (not @(rf/subscribe [:new-chat-read-only?]))
     :folded? (boolean (:folded? @(rf/subscribe [:fold-state])))
     :index index :n (count pictures)}))

(defn- column
  "The slots ids, top to bottom, each in its state."
  [ids states]
  (into [:<>] (for [id ids] ^{:key id} [slot-button id (states id)])))

(defn- foot
  "The rail's foot: the update pill, your profile circle (whose chats, the menu with Settings) and the
  version's line, at the bottom, off the chats' rail (README decision 73)."
  []
  [:div.edge-foot
   [update-pill/pill]
   [:f> menu/button]
   [update-pill/updated]])

(defn- mic-slot
  "The mic's circle, one slot's size whether the mic shows, cannot work here or has moved out."
  [oc]
  [:div.edge-mic
   (when (composer-shown?) [:f> mic/button (if oc (:ck oc) :new) {:rail? true}])])

(defn- edge-view []
  (let [oc @(rf/subscribe [:open-chat])
        states (pure/slot-states (now oc))]
    [:div.thumb-edge
     [:div.edge-over (column pure/over states)]
     [mic-slot oc]
     [:div.edge-under (column pure/under states) [foot]]]))

(defn edge
  "The edge; mounting it asks the hub whether it has a listen service (for My words)."
  []
  (r/with-let [_ (rf/dispatch [:words/load])]
    [edge-view]))
