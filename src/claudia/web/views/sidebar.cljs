(ns claudia.web.views.sidebar
  "The chat list: every allowed server's chats in one list, newest first."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [claudia.web.views.chat :as chat]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.settings :as settings]))

(def ^:private chat-colors
  #{"burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan" "sky" "blue" "indigo"
    "violet" "purple" "magenta" "pink" "brown" "ash"})

(defn chat-color-var [c] (when (chat-colors c) (str "var(--c-" c ")")))

(defn- when-label
  "Today: 14:05; this week: Mon; older: 3 Oct."
  [at]
  (when at
    (let [d (js/Date. at)
          now (js/Date.)
          age (- (.getTime now) at)]
      (cond
        (= (.toDateString d) (.toDateString now))
        (.toLocaleTimeString d js/undefined #js {:hour "2-digit" :minute "2-digit"})
        (< age (* 6 24 3600 1000)) (.toLocaleDateString d js/undefined #js {:weekday "short"})
        :else (.toLocaleDateString d js/undefined #js {:day "numeric" :month "short"})))))

(defn- row-view [{:keys [id server title preview waiting running unseen color off] :as row} active? multi? & [{:keys [read-only? title-view when-text on-click]}]]
  [:button.chat-row {:class [(when active? "active") (when (and unseen (not read-only?)) "unseen") (when off "off")]
                     :style {"--chat" (chat-color-var color)}
                     :on-click (or on-click #(rf/dispatch [:nav/open [server id]]))
                     :on-context-menu #(chatmenu/open-at! [server id] %)}
   [dot/chat-dot row]
   [:span.cs-text
    [:span.cs-l1
     [:span.t (or title-view (if (str/blank? title) "New chat" title))]
     (when read-only? [settings/lock-icon])
     (when multi? [chat/server-mark server])
     [:span.when (or when-text (when-label (:at row)))]]
    [:span.cs-l2 {:class (cond waiting "waiting" running "working")}
     (cond (and waiting (str/blank? preview)) "Waits for you"
           (and running (str/blank? preview)) "Working…"
           :else preview)]]])

(defn- marked
  "A search hit's snippet: \u0001…\u0002 marks the words found."
  [snip]
  (into [:span.snip]
        (map-indexed (fn [i part] (if (odd? i) [:mark part] part))
                     (str/split (str snip) #"[\u0001\u0002]"))))

(defn- found-list [found rows-by multi? ro]
  (let [hits (for [[server fs] found, f fs :let [r (rows-by [server (:id f)])] :when r] [r f])]
    (if (empty? hits)
      [:p.cs-empty.muted.small (if (empty? found) "Searching…" "Nothing said matches.")]
      [:<>
       (for [[r f] hits]
         ^{:key (str (:server r) "/" (:id r))}
         [:div.found {:style {"--chat" (chat-color-var (:color r))}}
          [row-view r false multi? {:read-only? ro
                                    :when-text (if (pos? (:count f)) (str (:count f) " hit" (when (not= 1 (:count f)) "s")) nil)}]
          (for [h (:hits f)]
            ^{:key (:uuid h)}
            [:button.found-hit {:class (:role h) :on-click #(rf/dispatch [:nav/open [(:server r) (:id r)]])}
             [:span.who (if (= "user" (:role h)) "you" "claude")]
             [marked (:snippet h)]])])])))

(defn- sessions-list [sessions]
  (when (seq (mapcat val sessions))
    [:<>
     [:div.cs-section "~/Work sessions"]
     (for [[server list] sessions, s list]
       ^{:key (str server "/" (:id s))}
       [:button.chat-row.session-row {:on-click #(rf/dispatch [:ops/adopt server (:id s)])}
        [:span.rail-dot "·"]
        [:span.cs-text [:span.cs-l1 [:span.t (:title s)] [:span.when (when-label (:at s))]]]])]))

(defn- chat-list []
  (let [rows @(rf/subscribe [:rows])
        open @(rf/subscribe [:open])
        multi? @(rf/subscribe [:multi-server?])
        me @(rf/subscribe [:me])
        ro @(rf/subscribe [:read-only?])
        {:keys [archived? all-sessions?]} @(rf/subscribe [:list-mode])
        {:keys [text found]} @(rf/subscribe [:search])
        sessions (when (and all-sessions? (not archived?)) @(rf/subscribe [:sessions]))
        rows-by (into {} (map (juxt (juxt :server :id) identity)) rows)]
    [:aside.sidebar
     [:div.cs-head
      [settings/title-menu]
      [settings/gear-button]
      (when-not ro
        [:button.cs-icon.cs-add {:title "New chat" :on-click #(rf/dispatch [:nav/new])}
         [:svg {:viewBox "0 0 24 24"} [:path {:d "M12 5v14M5 12h14"}]]])]
     (when archived?
       [:button.cs-archived {:on-click #(rf/dispatch [:ops/archived false])}
        [:svg {:viewBox "0 0 20 20"} [:path {:d "M12.5 4L6.5 10l6 6"}]] "Back to chats"])
     [:label.search-box.cs-search
      [:input.search {:placeholder "Search chats" :value (or text "")
                      :on-change #(rf/dispatch [:ops/search (.. % -target -value)])
                      :on-key-down #(when (= "Escape" (.-key %)) (rf/dispatch [:ops/search ""]))}]]
     [:div.cs-list
      (cond
        (nil? me) nil
        found [found-list found rows-by multi? ro]
        (empty? rows) [:p.cs-empty.muted (if archived? "No archived chats." "No chats yet.")]
        :else (for [r rows]
                ^{:key (str (:server r) "/" (:id r))}
                [row-view r (= open [(:server r) (:id r)]) multi? {:read-only? ro}]))
      (when-not found [sessions-list sessions])]
     (when-not ro
       [:button.cs-fab.phone-only {:title "New chat" :on-click #(rf/dispatch [:nav/new])}
        [:svg {:viewBox "0 0 24 24"} [:path {:d "M12 5v14M5 12h14"}]]])]))

(defn sidebar []
  (if (:settings? @(rf/subscribe [:list-mode]))
    [:aside.sidebar.settings-side [settings/settings-pane]]
    [chat-list]))
