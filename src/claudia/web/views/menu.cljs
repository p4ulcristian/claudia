(ns claudia.web.views.menu
  "The settings menu (README decision 24): a gear at the bottom left (the rail's foot; the list's foot
  while the list is open, and on a phone) opens a small menu: the default plan's 5-hour and week use
  (a click opens Settings, where the plans are), Machines (the Systems popup in the menu's place),
  Archived chats, Forms, the Desktop popups switch, More settings, and the version (the sounds
  switch is the bell in the chat's header).
  Every item that has more opens its own full screen."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.pure.usage :as usage-pure]
            [claudia.web.views.systems :as systems]
            [claudia.web.views.usage :as usage]))

;; ---- what the items do ----

(defn- open-settings! []
  (rf/dispatch [:sidebar/toggle true])
  (rf/dispatch [:ops/settings true]))

(defn- open-archived! []
  (rf/dispatch [:sidebar/toggle true])
  (rf/dispatch [:ops/archived true]))

(defn- open-forms! [] (rf/dispatch [:gallery/open true]))

;; ---- the parts ----

(def ^:private chev [:svg.mn-chev {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M7.5 4l6 6-6 6"}]])

(def gear-icon
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:circle {:cx 10 :cy 10 :r 2.6}]
   [:path {:d "M10 2.5v2.2M10 15.3v2.2M2.5 10h2.2M15.3 10h2.2M4.7 4.7l1.6 1.6M13.7 13.7l1.6 1.6M4.7 15.3l1.6-1.6M13.7 6.3l1.6-1.6"}]
   [:circle {:cx 10 :cy 10 :r 5.4}]])

(defn- limit-bar
  "One limit: its line (\"Week 56% · resets in 52 h\") and a bar coloured by how much is used."
  [label l now]
  (let [p (or (usage-pure/ring-percent l now) 0)]
    [:span.mn-limit {:class (usage-pure/level p)}
     [:span.mn-limit-text (usage-pure/limit-text label l now)]
     [:span.mn-bar [:i {:style {:width (str (min 100 p) "%")}}]]]))

(defn- plan-card
  "The default plan (the one a new chat starts on) with its 5-hour and week use; a click: Settings."
  [act]
  (let [now (hooks/use-now)
        tokens @(rf/subscribe [:tokens])
        live @(rf/subscribe [:live-limits])
        in-use (get-in tokens [:inUse :name])
        row (some #(when (:in-use %) %) (usage-pure/token-rows (:tokens tokens) in-use live))]
    [:button.mn-plan {:role "menuitem" :on-click (act open-settings!) :title "Claude plans and tokens"}
     [:span.mn-plan-name (if in-use (str in-use "'s plan") "Claude plan") chev]
     (limit-bar "5-hour" (or (:five row) (usage-pure/limit-of (:limits live) "five_hour")) now)
     (limit-bar "Week" (or (:week row) (usage-pure/limit-of (:limits live) "seven_day")) now)]))

(defn- item [icon label note on-click]
  [:button.mn-item {:role "menuitem" :on-click on-click}
   [:span.mn-icon icon]
   [:span.mn-label label (when note [:span.mn-note note])]
   chev])

(def ^:private archive-icon
  [:svg {:viewBox "0 0 16 16" :aria-hidden true}
   [:rect {:x 1.5 :y 2.5 :width 13 :height 3.5 :rx 0.8}]
   [:path {:d "M2.8 6v6.5a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1V6M6.3 9h3.4"}]])

(def ^:private forms-icon
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:rect {:x 3 :y 3 :width 6 :height 6 :rx 1.5}] [:circle {:cx 14 :cy 6 :r 3}] [:path {:d "M3 15h6M11 12.5l3 3 3-3"}]])

(defn- version-line []
  (let [server @(rf/subscribe [:settings-server])
        me @(rf/subscribe [:server-me server])
        label @(rf/subscribe [:server-label server])]
    (when me
      [:div.mn-foot "Claudia " [:span.mono (or (:commit me) (str "v" (:version me)))] (when server (str " · " label))])))

(defn- menu-items
  "The menu's rows. act wraps an item's action (it closes the menu first); machines! swaps the menu
  for the Systems popup."
  [act machines!]
  [:<>
   [:f> plan-card act]
   (item [systems/computer-icon] "Machines" "CPU, memory, disks" machines!)
   (item archive-icon "Archived chats" nil (act open-archived!))
   (item forms-icon "Forms" "everything Claude can draw" (act open-forms!))
   [:div.mn-sep]
   [:div.mn-switches [alerts/popups-switch]]
   [:div.mn-sep]
   (item gear-icon "More settings" "GitHub, colours, servers" (act open-settings!))
   [version-line]])

(defn- menu-pop [{:keys [box close! machines!]}]
  (let [menu (h/use-ref nil)
        act (fn [f] (fn [] (close!) (f)))]
    (chatmenu/use-dismiss! (fn [t] (or (some-> (.-current menu) (.contains t)) (some-> (.-current box) (.contains t))))
                           close!)
    (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens]) js/undefined) [])
    [:div.chat-menu.mn-pop {:ref #(set! (.-current menu) %) :role "menu" :aria-label "Settings"
                            :style (usage/pop-place (.-current box) true)}
     (menu-items act machines!)]))

(defn button
  "The gear at the bottom left and what it opens: the menu, or the Systems popup from its Machines."
  []
  (let [[open set-open] (h/use-state nil)
        box (h/use-ref nil)
        close! #(set-open nil)]
    [:<>
     [:button.mn-gear {:ref #(set! (.-current box) %) :class (when open "on")
                       :aria-label "Settings" :aria-haspopup "menu" :aria-expanded (boolean open)
                       :data-tip (when-not open "Settings")
                       :on-click #(set-open (if open nil :menu))}
      gear-icon]
     (case open
       :menu [:f> menu-pop {:box box :close! close! :machines! #(set-open :machines)}]
       :machines [:f> systems/popup {:box box :close close!}]
       nil)]))
