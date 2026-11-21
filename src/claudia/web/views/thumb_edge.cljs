(ns claudia.web.views.thumb-edge
  "The right edge of every screen wider than a phone, touch or mouse (README decisions 40, 73, 92,
  105): a big tablet held in two hands is held at its sides partway up, so the right thumb rests on
  the right edge at middle height; a desktop gets the same column, its buttons mouse-sized
  (reach.css), so every device keeps the same places. There a rail on the right wall, one column
  of nine slots of one size: Search, System, Files, Profiles (toggles: lit while their sheet is on
  top, a press then closes it), Stop, New chat, Fork (the open chat up to its last finished turn,
  README decision 99), Archive (the open chat, asked first; Unarchive for an archived one) and
  Settings at the bottom (a toggle too; README decision 120); README decision 117 moved Files, Fork and Archive here from the chat's header and
  took the picture steps off (the picture has its own). Each a button with its name
  under its icon, at least 12 mm on a touch screen, see-through at rest. A button never comes or goes, so nothing
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
            [claudia.web.views.head-pill :as hp]
            [claudia.web.views.mic :as mic]
            [claudia.web.views.pure.edge :as pure]
            [claudia.web.views.pure.menu :as menu-pure]
            [claudia.web.views.systems :as systems]
            [claudia.web.views.update-pill :as update-pill]))

(defn- svg-of [& paths] (into [:svg {:viewBox "0 0 24 24" :aria-hidden true}] paths))

(def ^:private slots
  "Each slot: its name under the icon, its tip, its icon and what a press does."
  {:search {:label "Search" :tip "Search chats" :act search-open/open!
            :icon (svg-of [:circle {:cx 11 :cy 11 :r 6}] [:path {:d "M20 20l-4.5-4.5"}])}
   :system {:label "System" :tip "System: health, ports, services" :icon [systems/system-icon]
            :act #(rf/dispatch [:right/view :system])}
   :files {:label "Files" :tip "File Browser" :icon [hp/icon :folder]
           :act #(rf/dispatch [:right/view :files])}
   :prompts {:label "Profiles" :tip "Prompt profiles: edit them, pick this chat's" :icon icons/prompts-icon
             :act #(rf/dispatch [:right/view :prompts])}
   :settings {:label "Settings" :tip "Settings, your dictation words too" :icon icons/settings-icon
              :act #(rf/dispatch [:right/view :settings])}
   :stop {:label "Stop" :tip "Stop the running turn (Esc)" :act #(rf/dispatch [:chat/stop])
          :icon (svg-of [:rect {:x 6 :y 6 :width 12 :height 12 :rx 2}])}
   :new {:label "New chat" :tip "New chat (Alt+N)" :icon (svg-of [:path {:d "M12 5v14M5 12h14"}])
         :act #(rf/dispatch [:nav/new])}})

(defn- fork-slot
  "The Fork slot for the open chat ck: a new chat with it up to its last finished turn, in the same
  folder (README decision 99); Forking… while the server makes it."
  [ck forking?]
  {:label (if forking? "Forking…" "Fork")
   :tip "A new chat with this one up to its last finished turn, in the same folder"
   :icon [hp/icon :fork]
   :act #(when-not forking? (rf/dispatch [:ops/fork ck nil]))})

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
  (if (and (= state :on) (contains? pure/sheet-of id))
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

(defn- now
  "What the slots' states depend on (pure/slot-states), read from the page; oc: the open chat."
  [oc]
  (let [ro? @(rf/subscribe [:read-only?])]
    {:running? (boolean (when oc (:running @(rf/subscribe [:chat (:ck oc)]))))
     :archivable? (boolean (and (:row oc) (not (:pending? oc)) (not ro?)))
     :forking? (:forking? (menu-pure/fork-state @(rf/subscribe [:fork]) (:ck oc)))
     :new? (not @(rf/subscribe [:new-chat-read-only?]))
     :read-only? ro?
     :view @(rf/subscribe [:right-view])}))

(defn- column
  "The slots ids, top to bottom, each in its state; oc: the open chat (the Fork and Archive slots')."
  [ids states oc]
  (into [:<>] (for [id ids]
                ^{:key id} [slot-button id (states id)
                            (case id
                              :archive (archive-slot (:ck oc) (:archived (:row oc)))
                              :fork (fork-slot (:ck oc) (= :on (states id)))
                              (slots id))])))

(defn- foot
  "The rail's foot: the update pill, your profile circle (whose chats, the menu with Settings) and the
  version's line, at the bottom, off the chats' rail (README decision 73)."
  []
  [:div.edge-foot
   [update-pill/pill]
   [:f> menu/button]
   [update-pill/updated]])

(defn- edge-view []
  (let [oc @(rf/subscribe [:open-chat])
        states (pure/slot-states (now oc))]
    [:div.thumb-edge
     [:div.edge-over (column pure/over states oc)]
     [:div.edge-under (column pure/under states oc) [foot]]
     [mic/floating oc]]))

(defn edge "The edge." [] [edge-view])
