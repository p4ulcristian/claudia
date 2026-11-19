(ns claudia.web.views.folder-detail
  "The Folders modal's right pane for a folder (README decisions 61, 68 and 69): its name (★ when
  bookmarked), full path and server, its facts in quiet lines (chats that used it, last touched,
  size, git), Use for this chat (the chat's folder becomes it), New chat here, Pin/Unpin, and the
  recent chats there (a click opens one). Nothing here deletes: Claude does that when asked."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.format :as format]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.folders :as pure]))

(defn- use-facts
  "The folder's facts, asked again every 2 s while its size is still being measured; [facts error]."
  [server p]
  (let [[facts set-facts] (h/use-state nil)
        on? (or (nil? facts) (boolean (:measuring facts)))
        error (hooks/use-poll {:on? on? :ms 2000 :url-of (constantly (folders/facts-url server p))
                               :on-answer set-facts}
                              [server p on?])]
    [facts error]))

(defn- fact [label value]
  [:<> [:dt label] [:dd value]])

(defn- facts-list
  "The folder's facts as quiet label-value lines: chats that used it, last touched, size, git."
  [{:keys [chats mtime git] :as facts}]
  [:dl.fd-facts
   (fact "Chats here" (if facts (str (count chats) (when (>= (count chats) 20) "+")) "…"))
   (fact "Last touched" (if facts (or (format/when-text mtime) "unknown") "…"))
   (fact "Size" (if facts (pure/size-text facts) "…"))
   (fact "Git" (cond (nil? facts) "…" git (pure/git-text git) :else "not in a repo"))])

(defn- chat-list [server chats]
  (when (seq chats)
    [:div.fd-chats
     [:div.fd-head "Recent chats here"]
     (for [{:keys [id title at]} chats]
       ^{:key id}
       [:button.fd-chat {:on-click #(rf/dispatch [:folders/open-chat [server id]])}
        [:span.fd-chat-title (or (not-empty title) "Chat")]
        [:span.fd-chat-at (format/when-text at)]])]))

(defn- buttons [{:keys [server p bookmarked?]}]
  [:div.fd-actions
   [:button.fd-btn.primary {:on-click #(rf/dispatch [:folders/use p])} "Use for this chat"]
   [:button.fd-btn {:on-click #(rf/dispatch [:folders/new-chat server p])} "New chat here"]
   [:button.fd-btn {:on-click #(rf/dispatch [:bookmarks/toggle server p])} (if bookmarked? "Unpin" "Pin")]])

(defn panel
  "The folder p on server. bookmarked?: ★ and Unpin."
  [{:keys [server p bookmarked? label]}]
  (let [[facts error] (use-facts server p)]
    [:div.fd-detail
     [:h2.fd-title (pure/base-name p) (when bookmarked? [:span.fd-star {:title "Bookmarked"} " ★"])]
     [:div.fd-where.mono (str (format/tilde p) " · " label)]
     (when error [:p.fd-warn error])
     [facts-list facts]
     [buttons {:server server :p p :bookmarked? bookmarked?}]
     [chat-list server (:chats facts)]]))
