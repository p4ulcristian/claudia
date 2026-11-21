(ns claudia.web.views.thumb-edge
  "The right edge of a touch tablet (README decisions 40, 73, 92): a big tablet held in two hands is
  held at its sides partway up, so the right thumb rests on the right edge at middle height. There a
  rail on the right wall, one column of nine slots of one size: Search, System, Prompts,
  Settings (toggles: lit while their sheet is on top, a press then closes it), Stop, New chat,
  Archive (the open chat, asked first; Unarchive for an archived one), ‹ (the previous picture), › (the next picture). Each a button with its name
  under its icon, at least 12 mm, see-through at rest. A button never comes or goes, so nothing
  shifts: one that cannot act now is passive (greyed and disabled), Stop is red while the open
  chat's turn runs (claudia.web.views.pure.edge). At its bottom, outside the nine, the foot the
  chats' rail had: the update pill, your profile circle (its menu holds Settings, Archived chats,
  Prompts) and the version's line, so the chats have the left edge to themselves.
  The mic floats over the page beside it (README decision 94, claudia.web.views.mic); the composer
  leaves its own mic out on these screens (claudia.web.views.chat)."
  (:require [claudia.web.search-open :as search-open]
            [re-frame.core :as rf]
            [claudia.web.views.head :as head]
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
   :system {:label "System" :tip "System: health, ports, services" :icon [systems/system-icon]
            :act #(rf/dispatch [:right/view :system])}
   :prompts {:label "Prompts" :tip "Prompt profiles" :icon icons/prompts-icon
             :act #(rf/dispatch [:right/view :prompts])}
   :settings {:label "Settings" :tip "Settings, your dictation words too" :icon icons/settings-icon
              :act #(rf/dispatch [:right/view :settings])}
   :stop {:label "Stop" :tip "Stop the running turn (Esc)" :act #(rf/dispatch [:chat/stop])
          :icon (svg-of [:rect {:x 6 :y 6 :width 12 :height 12 :rx 2}])}
   :new {:label "New chat" :tip "New chat (Alt+N)" :icon (svg-of [:path {:d "M12 5v14M5 12h14"}])
         :act #(rf/dispatch [:nav/new])}
   :prev {:label "Previous" :tip "Previous picture" :icon (svg-of [:path {:d "M14.5 5.5L8 12l6.5 6.5"}])
          :act #(rf/dispatch [:picture/step -1])}
   :next {:label "Next" :tip "Next picture" :icon (svg-of [:path {:d "M9.5 5.5L16 12l-6.5 6.5"}])
          :act #(rf/dispatch [:picture/step 1])}})

(defn- archive-slot
  "The Archive slot for the open chat ck: Archive (asked first, as the header's) or Unarchive for an
  archived one."
  [ck archived?]
  {:label (if archived? "Unarchive" "Archive")
   :tip (if archived? "Back to the open chats" "Archive this chat")
   :icon [head/archive-icon archived?]
   :act #(rf/dispatch (if archived? [:chat/archive ck false] [:archive/ask ck]))})

(defn- press
  "What a press on slot id in state does: a lit sheet slot closes its sheet, back to what lay under
  it (the Drawing board when it was the only one); any other press acts."
  [id state act]
  (if (= state :on)
    #(rf/dispatch [:right/close (pure/sheet-of id)])
    act))

(defn- slot-button
  "One slot's button in its state (:shown, :on, :passive or :red); passive, it is disabled; a sheet
  slot is a toggle (aria-pressed). spec: the slot's label, tip, icon and act."
  [id state spec]
  (let [{:keys [label tip icon act]} spec
        sheet? (contains? pure/sheet-of id)]
    [:button.edge-step {:type "button" :class (name state) :disabled (= state :passive)
                        :aria-pressed (when sheet? (= state :on))
                        :aria-label tip :data-tip tip :on-click (press id state act)}
     icon [:span.edge-name label]]))

(defn- composer-shown?
  "The composer shows (claudia.web.views.chat/pane-foot): not read-only, and a server to chat on."
  []
  (let [me @(rf/subscribe [:me])]
    (and (not @(rf/subscribe [:read-only?])) (seq (:servers me)))))

(defn- now
  "What the slots' states depend on (pure/slot-states), read from the page; oc: the open chat."
  [oc]
  (let [{:keys [pictures index]} @(rf/subscribe [:picture-history])]
    {:running? (boolean (when oc (:running @(rf/subscribe [:chat (:ck oc)]))))
     :archivable? (boolean (and (:row oc) (not (:pending? oc)) (not @(rf/subscribe [:read-only?]))))
     :new? (not @(rf/subscribe [:new-chat-read-only?]))
     :folded? (boolean (:folded? @(rf/subscribe [:fold-state])))
     :view @(rf/subscribe [:right-view])
     :index index :n (count pictures)}))

(defn- column
  "The slots ids, top to bottom, each in its state; oc: the open chat (the Archive slot's)."
  [ids states oc]
  (into [:<>] (for [id ids]
                ^{:key id} [slot-button id (states id)
                            (if (= id :archive) (archive-slot (:ck oc) (:archived (:row oc))) (slots id))])))

(defn- foot
  "The rail's foot: the update pill, your profile circle (whose chats, the menu with Settings) and the
  version's line, at the bottom, off the chats' rail (README decision 73)."
  []
  [:div.edge-foot
   [update-pill/pill]
   [:f> menu/button]
   [update-pill/updated]])

(defn- floating-mic
  "The mic, floating over the page out of the rail (README decision 94), speaking into the open chat."
  [oc]
  [:div.edge-mic
   (when (composer-shown?) [:f> mic/button (if oc (:ck oc) :new) {:floating? true}])])

(defn- edge-view []
  (let [oc @(rf/subscribe [:open-chat])
        states (pure/slot-states (now oc))]
    [:div.thumb-edge
     [:div.edge-over (column pure/over states oc)]
     [:div.edge-under (column pure/under states oc) [foot]]
     [floating-mic oc]]))

(defn edge "The edge." [] [edge-view])
