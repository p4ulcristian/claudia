(ns claudia.web.views.rail
  "The rail (desktop, the list closed; web/app.tsx of the TypeScript Claudia): + for a new chat, the
  search, then a circle per chat with its title under it and, on the hub, its server's name; passive
  (ash) chats last, above a chalk line. At its foot: sounds, the Claude subscription in use (a click
  switches it; the Forms window stays in Settings), the usage rings. Circles
  slide to their new places when the order changes; a new one grows in."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.alerts :as alerts]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.pure.menu :as pure]
            [claudia.web.views.pure.sidebar :as sidebar-pure]
            [claudia.web.views.sidebar :as sidebar]
            [claudia.web.views.usage :as usage]))

(defn- use-slide!
  "Each [data-slide] child of box is measured after every change of order and animated from where
  it was; a new one grows in."
  [box order]
  (let [seen (h/use-ref nil)]
    (h/use-layout-effect
     (fn []
       (if-let [^js el (.-current box)]
         (let [top (- (.-top (.getBoundingClientRect el)) (.-scrollTop el))
               items (array-seq (.querySelectorAll el "[data-slide]"))
               now (into {} (for [^js d items] [(.. d -dataset -slide) (- (.-top (.getBoundingClientRect d)) top)]))
               before (.-current seen)]
           (set! (.-current seen) now)
           (when (and before (not (.-matches (js/matchMedia "(prefers-reduced-motion: reduce)"))))
             (doseq [^js d items
                     :let [k (.. d -dataset -slide) was (get before k) is (get now k)]]
               (cond
                 (nil? was) (.animate d #js [#js {:opacity 0 :transform "scale(0.4)"} #js {:opacity 1 :transform "none"}]
                                      #js {:duration 260 :easing "cubic-bezier(.2,.8,.3,1.2)"})
                 (> (js/Math.abs (- was is)) 1)
                 (.animate d #js [#js {:transform (str "translateY(" (- was is) "px)")} #js {:transform "none"}]
                           #js {:duration 420 :easing "cubic-bezier(.3,.7,.2,1)"})))))
         (set! (.-current seen) nil))
       nil)
     [order])))

(defn- speaker [on?]
  [:svg {:viewBox "0 0 20 20" :aria-hidden true}
   [:path {:d "M3 8h3l4-3.5v11L6 12H3z"}]
   (if on?
     [:path {:d "M13 7.5c1 1.4 1 3.6 0 5M15.4 5.5c2 2.5 2 6.5 0 9"}]
     [:path {:d "M13.5 8l4 4M17.5 8l-4 4"}])])

(defn- switch-to!
  "Every chat on server to the saved token n."
  [server n]
  (rf/dispatch [:ops/put-now {:url (str "/s/" (js/encodeURIComponent server) "/api/claude-token/use")
                              :method "POST" :body {:name n}
                              :on-done (fn [ok _] (when ok (rf/dispatch [:ops/tokens server ok])))}]))

(defn- plan-menu
  "The saved tokens of server, beside the button (box); a pick switches every chat there."
  [{:keys [server box saved account close]}]
  (let [menu (h/use-ref nil)
        rc (.getBoundingClientRect ^js (.-current box))]
    (chatmenu/use-dismiss! (fn [t] (or (some-> (.-current menu) (.contains t))
                                       (some-> (.-current box) (.contains t))))
                           close)
    [:div.chat-menu.plan-menu {:ref #(set! (.-current menu) %) :role "menu"
                               :style {:left (+ (.-right rc) 8) :bottom (max 6 (- js/innerHeight (.-bottom rc)))}}
     [:div.cm-title (str "Claude subscription · " @(rf/subscribe [:server-label server]))]
     (for [n saved]
       ^{:key n}
       [:button.cm-item {:role "menuitemradio" :aria-checked (= n account) :class (when (= n account) "on")
                         :on-click (fn []
                                     (close)
                                     (when (not= n account) (switch-to! server n)))}
        (str n "'s plan")])
     [:button.cm-item.plan-add {:role "menuitem" :on-click (fn [] (close) (rf/dispatch [:ops/settings true]))}
      "Add a token in Settings"]]))

(defn- plan-button
  "Which Claude subscription the chats on the open chat's server (else the new chat's) run on: the
  token in use's initials; a click lists the server's saved tokens to switch to."
  []
  (let [open @(rf/subscribe [:open])
        server (or (first open) @(rf/subscribe [:new-server]))
        tokens @(rf/subscribe [:tokens server])
        account (get-in tokens [:inUse :name])
        saved (mapv :name (:tokens tokens))
        [menu? set-menu] (h/use-state false)
        box (h/use-ref nil)]
    (h/use-effect (fn [] (rf/dispatch [:ops/load-tokens server]) (set-menu false) nil) [server])
    (when server
      [:<>
       [:button.rail-forms.rail-plan
        {:ref #(set! (.-current box) %)
         :class (when menu? "on")
         :data-tip (pure/plan-tip account @(rf/subscribe [:multi-server?]) @(rf/subscribe [:server-label server]) saved)
         :aria-label (str "Claude subscription" (when account (str ": " account)))
         :aria-haspopup "menu" :aria-expanded menu?
         :on-click #(if (> (count saved) 1) (set-menu (not menu?)) (rf/dispatch [:ops/settings true]))}
        [:span.rail-plan-mark (if account (pure/initials account) "?")]]
       (when menu?
         [:f> plan-menu {:server server :box box :saved saved :account account :close #(set-menu false)}])])))

(defn- rail-chat
  "A chat's circle with its title (and its server on the hub)."
  [{:keys [rows i open multi? ro projects]} {:keys [server id title color off folder] :as r}]
  (let [k [server id]
        passive? (= "ash" color)
        project (:name (get projects [server folder]))
        label @(rf/subscribe [:server-label server])]
    [:button.rail-chat {:data-slide (str server "/" id)
                        :class [(when (= k open) "on") (when off "off")
                                (when passive? "passive")
                                (when (and passive? (not= "ash" (:color (get rows (dec i))))) "first")
                                (when (and passive? (= i (dec (count rows)))) "last")]
                        :style {"--chat" (sidebar/chat-color-var color)}
                        :data-tip (str title (when project (str " · " project))
                                       (when multi? (str " · " label (when off " is off"))))
                        :data-tip-color (sidebar/chat-color-var color)
                        :aria-label title
                        :on-click #(sidebar/pick! k (= k open) %)
                        :on-context-menu #(chatmenu/open-at! k %)}
     [dot/chat-dot r (and (not ro) (:unseen r) (not= k open))]
     [:span.rail-name title]
     (when multi? [:span.rail-server label])]))

(defn- sound-button [sounds?]
  [:button.rail-forms.rail-sound {:class (when sounds? "on")
                                  :data-tip (if sounds? "Sounds on: click to mute" "Sounds off: click to hear when a chat finishes or waits")
                                  :aria-label "Sounds" :aria-pressed (boolean sounds?)
                                  :on-click #(alerts/set-sounds! (not sounds?))}
   [speaker sounds?]])

(defn- rail-top [ro]
  [:<>
   (when-not ro
     [:button.rail-dot.add {:title "New chat (Alt+N)" :on-click #(rf/dispatch [:nav/new])} "+"])
   [:button.rail-dot.add.rail-search {:data-tip "Search chats" :aria-label "Search chats"
                                      :on-click #(rf/dispatch [:sidebar/search])}
    [:svg {:viewBox "0 0 20 20" :aria-hidden true} [:circle {:cx 8.5 :cy 8.5 :r 5}] [:path {:d "M12.3 12.3L17 17"}]]]])

(defn rail []
  (let [{:keys [text]} @(rf/subscribe [:search])
        rows (sidebar-pure/passive-last (sidebar-pure/by-title @(rf/subscribe [:rows]) (sidebar-pure/search-key text)))
        ro @(rf/subscribe [:read-only?])
        m {:rows rows :open @(rf/subscribe [:open]) :multi? @(rf/subscribe [:multi-server?]) :ro ro
           :projects @(rf/subscribe [:projects])}
        box (h/use-ref nil)]
    (use-slide! box (str/join "," (map #(str (:server %) "/" (:id %)) rows)))
    [:nav.rail {:aria-label "Chats"}
     [:div.rail-dots {:ref #(set! (.-current box) %)}
      (rail-top ro)
      (for [[i r] (map-indexed vector rows)]
        ^{:key (str (:server r) "/" (:id r))}
        [rail-chat (assoc m :i i) r])]
     (sound-button (:sounds @alerts/on))
     (when-not ro [:f> plan-button])
     [:f> usage/rail-meters]]))
