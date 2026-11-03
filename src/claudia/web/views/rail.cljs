(ns claudia.web.views.rail
  "The rail, while the chat list is folded (desktop): + for a new chat, search, then a circle per
  chat (its project's logo or letter, its state badge) with its title and, with two servers or
  more, its server under it. Passive (ash) chats sit last. A click opens the chat."
  (:require [re-frame.core :as rf]
            [claudia.web.views.chatmenu :as chatmenu]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.sidebar :as sidebar]
            [claudia.web.views.usage :as usage]))

(defn rail []
  (let [rows @(rf/subscribe [:rows])
        open @(rf/subscribe [:open])
        multi? @(rf/subscribe [:multi-server?])
        ro @(rf/subscribe [:read-only?])
        rows (concat (remove #(= "ash" (:color %)) rows) (filter #(= "ash" (:color %)) rows))]
    [:nav.rail {:aria-label "Chats"}
     [:div.rail-dots
      (when-not ro
        [:button.rail-dot.add {:title "New chat" :on-click #(rf/dispatch [:nav/new])} "+"])
      [:button.rail-dot.add.rail-search {:title "Search chats"
                                         :on-click #(do (rf/dispatch [:sidebar/toggle])
                                                        (js/setTimeout (fn [] (some-> (js/document.querySelector ".cs-search input") .focus)) 60))}
       [:svg {:viewBox "0 0 20 20"} [:circle {:cx 8.5 :cy 8.5 :r 5}] [:path {:d "M12.3 12.3L17 17"}]]]
      (for [{:keys [server id title color off] :as r} rows
            :let [k [server id]]]
        ^{:key (str server "/" id)}
        [:button.rail-chat {:class [(when (= k open) "on") (when off "off") (when (= "ash" color) "passive")]
                            :style {"--chat" (sidebar/chat-color-var color)}
                            :title title
                            :on-click #(rf/dispatch [:nav/open k])
                            :on-context-menu #(chatmenu/open-at! k %)}
         [dot/chat-dot r]
         [:span.rail-name title]
         (when multi? [:span.rail-server @(rf/subscribe [:server-label server])])])]
     [:f> usage/rail-meters]]))
