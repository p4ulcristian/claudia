(ns claudia.web.views.menu
  "The profile menu (README decision 24): your profile circle at the bottom left (the rail's foot;
  the list's foot on a phone; on a touch tablet the foot of the mic's rail on the right, its menu
  opening to the left, README decision 73; 2026-11-18, it was a gear) shows whose chats
  show and opens a small menu: Whose chats (yours, or another person's, read only), your own
  plan's 5-hour and week use (your last pick in a chat's header, README decision 57; a click opens
  Settings, where the plans are), Machines, Archived chats, Forms, Prompts, the Desktop popups
  switch, More settings (Machines, Forms, Prompts and Settings open in the right half, README
  decisions 75 and 92; your dictation words are in Settings, decision 95), and the
  version (the sounds switch is the bell in the chat's header)."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.icons :as icons]
            [claudia.web.views.pure.menu :as menu-pure]
            [claudia.web.views.pure.settings :as settings-pure]
            [claudia.web.views.pure.usage :as usage-pure]
            [claudia.web.views.systems :as systems]
            [claudia.web.views.usage :as usage]))

;; ---- what the items do ----

;; Settings and the archived chats: over the list on a phone, in the chat column wider.
(defn- open-settings! [] (rf/dispatch [:right/view :settings]))

(defn- open-archived! [] (rf/dispatch [:ops/archived true]))

(defn- open-forms! [] (rf/dispatch [:right/view :forms]))

(defn- open-machines! [] (rf/dispatch [:right/view :machines]))

(defn- open-prompts! [] (rf/dispatch [:right/view :prompts]))


;; ---- the parts ----

(def ^:private chev [:svg.mn-chev {:viewBox "0 0 20 20" :aria-hidden true} [:path {:d "M7.5 4l6 6-6 6"}]])

(defn- limit-bar
  "One limit: its line (\"Week 56% · resets in 52 h\") and a bar coloured by how much is used."
  [label l now]
  (let [p (or (usage-pure/ring-percent l now) 0)]
    [:span.mn-limit {:class (usage-pure/level p)}
     [:span.mn-limit-text (usage-pure/limit-text label l now)]
     [:span.mn-bar [:i {:style {:width (str (min 100 p) "%")}}]]]))

(defn- plan-card
  "The person's own plan (their last pick in a chat's header: what a new chat starts on, README
  decision 57) with its 5-hour and week use; a click: Settings."
  [act]
  (let [now (hooks/use-now)
        tokens @(rf/subscribe [:tokens])
        live @(rf/subscribe [:live-limits])
        in-use (get-in tokens [:pick :name])
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
      [:div.mn-foot "Claudia " [:span.mono (or (:commit me) (str "v" (:version me)))]
       (when (:sdk me) [:<> " · SDK " [:span.mono (:sdk me)]])
       (when server (str " · " label))])))

(defn- whose
  "Whose chats show: yours, or another person's (read only); a pick reconnects every server."
  [act]
  (let [view @(rf/subscribe [:view])
        people @(rf/subscribe [:people])
        me @(rf/subscribe [:me])
        owners @(rf/subscribe [:owners])
        mine (or (:name me) "Me")]
    (when (seq people)
      [:<>
       [:div.mn-head "Whose chats"]
       (for [[k label] (settings-pure/whose-items people)]
         ^{:key (str k)}
         [:button.mn-who {:class (when (= k view) "on") :role "menuitemradio" :aria-checked (= k view)
                          :on-click (act #(when (not= k view) (rf/dispatch [:ops/view k])))}
          [:span.mn-face.small (if-let [p (menu-pure/picture-of me owners k)] [:img {:src p :alt ""}] (menu-pure/initials (or k mine)))]
          [:span.mn-label label (when k [:span.mn-note "read only"])]
          (when (= k view) [:span.mn-tick "✓"])])
       [:div.mn-sep]])))

(defn- menu-items
  "The menu's rows. act wraps an item's action (it closes the menu first)."
  [act]
  [:<>
   [whose act]
   [:f> plan-card act]
   (item [systems/computer-icon] "Machines" "CPU, memory, disks" (act open-machines!))
   (item archive-icon "Archived chats" nil (act open-archived!))
   (item forms-icon "Forms" "everything Claude can draw" (act open-forms!))
   (item icons/prompts-icon "Prompts" "profiles a chat can pick" (act open-prompts!))
   [:div.mn-sep]
   [:div.mn-switches [alerts/popups-switch]]
   [:div.mn-sep]
   (item icons/settings-icon "More settings" "Claude subscriptions, GitHub" (act open-settings!))
   [version-line]])

(defn- menu-pop [{:keys [box close!]}]
  (let [menu (h/use-ref nil)
        act (fn [f] (fn [] (close!) (f)))]
    (chatmenu/use-dismiss! (fn [t] (or (some-> (.-current menu) (.contains t)) (some-> (.-current box) (.contains t))))
                           close!)
    (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens]) js/undefined) [])
    [:div.chat-menu.mn-pop {:ref #(set! (.-current menu) %) :role "menu" :aria-label "Settings"
                            :style (usage/pop-place (.-current box) (if (some-> (.-current box) (.closest ".thumb-edge")) :left true))}
     (menu-items act)]))

(defn button
  "The profile circle at the bottom left (whose chats show) and the menu it opens."
  []
  (let [[open set-open] (h/use-state nil)
        box (h/use-ref nil)
        close! #(set-open nil)
        {:keys [initials other? picture] nm :name} (menu-pure/profile-face @(rf/subscribe [:me]) @(rf/subscribe [:view])
                                                                           @(rf/subscribe [:owners]))]
    [:<>
     [:button.mn-gear.mn-face {:ref #(set! (.-current box) %) :class [(when open "on") (when other? "other")]
                               :aria-label (str nm ": whose chats, settings") :aria-haspopup "menu" :aria-expanded (boolean open)
                               :data-tip (when-not open (if other? (str nm "'s chats, read only") (str nm " · settings")))
                               :on-click #(set-open (if open nil :menu))}
      (if picture [:img {:src picture :alt ""}] initials)]
     (when open [:f> menu-pop {:box box :close! close!}])]))
