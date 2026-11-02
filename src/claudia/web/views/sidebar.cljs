(ns claudia.web.views.sidebar
  "The chat list: every allowed server's chats in one list, newest first."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [claudia.web.views.chat :as chat]))

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

(defn- state-badge [{:keys [waiting running unseen]}]
  (cond
    waiting [:span.state-badge.waiting "?"]
    running [:span.state-badge.thinking [:i] [:i] [:i]]
    unseen [:span.state-badge.done "✓"]))

(defn- row-view [{:keys [id server title preview waiting running unseen color off] :as row} active? multi?]
  [:button.chat-row {:class [(when active? "active") (when unseen "unseen") (when off "off")]
                     :style {"--chat" (chat-color-var color)}
                     :on-click #(rf/dispatch [:nav/open [server id]])}
   [:span.rail-dot
    (str/upper-case (subs (str (if (str/blank? title) "·" title)) 0 1))
    [state-badge row]]
   [:span.cs-text
    [:span.cs-l1
     [:span.t (if (str/blank? title) "New chat" title)]
     (when multi? [chat/server-mark server])
     [:span.when (when-label (:at row))]]
    [:span.cs-l2 {:class (cond waiting "waiting" running "working")}
     (cond (and waiting (str/blank? preview)) "Waits for you"
           (and running (str/blank? preview)) "Working…"
           :else preview)]]])

(defn sidebar []
  (let [rows @(rf/subscribe [:rows])
        open @(rf/subscribe [:open])
        multi? @(rf/subscribe [:multi-server?])
        me @(rf/subscribe [:me])]
    [:aside.sidebar
     [:div.cs-head
      [:h2.cs-title "Chats"]
      [:button.cs-icon.cs-add {:title "New chat" :on-click #(rf/dispatch [:nav/new])}
       [:svg {:viewBox "0 0 24 24"} [:path {:d "M12 5v14M5 12h14"}]]]]
     [:div.cs-list
      (cond
        (nil? me) nil
        (empty? rows) [:p.cs-empty.muted "No chats yet."]
        :else (for [r rows]
                ^{:key (str (:server r) "/" (:id r))}
                [row-view r (= open [(:server r) (:id r)]) multi?]))]
     [:button.cs-fab.phone-only {:title "New chat" :on-click #(rf/dispatch [:nav/new])}
      [:svg {:viewBox "0 0 24 24"} [:path {:d "M12 5v14M5 12h14"}]]]]))
