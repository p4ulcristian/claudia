(ns claudia.web.views.folder-detail
  "The Folders modal's right side for the selected folder (README decisions 61 and 63): its name
  (★ when bookmarked), full path and server, tiles (chats that used it, last touched, size, git),
  the recent chats there (a click opens one), and Use for this chat (the chat's folder becomes it),
  New chat here, Pin/Unpin, Move to Trash… and Delete now…, both asking first in a dialog with the
  full path, the size and the work not pushed."
  (:require [re-frame.core :as rf]
            [reagent.hooks :as h]
            [claudia.web.folders :as folders]
            [claudia.web.format :as format]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.confirm :as confirm]
            [claudia.web.views.pure.folders :as pure]))

(defn- use-facts
  "The folder's facts, asked again every 2 s while its size is still being measured; [facts error]."
  [server p]
  (let [[facts set-facts] (h/use-state nil)
        on? (or (nil? facts) (boolean (:measuring facts)))
        error (hooks/use-poll {:on? on? :ms 2000 :url-of (constantly (folders/facts-url server p false))
                               :on-answer set-facts}
                              [server p on?])]
    [facts error]))

(defn- tile [label value]
  [:div.fd-tile [:span.fd-tile-label label] [:span.fd-tile-value value]])

(defn- tiles [{:keys [chats mtime git] :as facts}]
  [:div.fd-tiles
   (tile "Chats here" (if facts (str (count chats) (when (>= (count chats) 20) "+")) "…"))
   (tile "Last touched" (if facts (or (format/when-text mtime) "unknown") "…"))
   (tile "Size" (if facts (pure/size-text facts) "…"))
   (tile "Git" (cond (nil? facts) "…" git (pure/git-text git) :else "not in a repo"))])

(defn- chat-list [server chats]
  (when (seq chats)
    [:div.fd-chats
     [:div.fd-head "Recent chats here"]
     (for [{:keys [id title at]} chats]
       ^{:key id}
       [:button.fd-chat {:on-click #(rf/dispatch [:folders/open-chat [server id]])}
        [:span.fd-chat-title (or (not-empty title) "Chat")]
        [:span.fd-chat-at (format/when-text at)]])]))

(defn- asked-body
  "What the delete dialog says: the full path, the size, the work not pushed (facts: deep facts)."
  [p facts error]
  [:div.fd-ask
   [:p.fd-ask-path.mono (or (:path facts) p)]
   (cond error [:p.fd-warn error]
         (nil? facts) [:p.muted "Looking at the folder…"]
         :else [:<>
                [:p (str "Size: " (pure/size-text facts))]
                (let [lines (pure/unpushed-lines facts)]
                  (if (seq lines)
                    [:<> [:p.fd-warn "Work that exists only here:"]
                     [:ul.fd-warn (for [l lines] ^{:key l} [:li l])]]
                    [:p.muted "No git work that is not pushed."]))])])

(defn- ask-view
  "The dialog before kind (:trash or :delete) of folder p on server; on-done after it went."
  [{:keys [server p kind on-done on-no]}]
  (let [[facts set-facts] (h/use-state nil)
        [error set-error] (h/use-state nil)
        go! #(-> (folders/act! server (if (= kind :trash) "trash" "delete") {:path p})
                 (.then (fn [_] (on-done)))
                 (.catch (fn [e] (set-error (.-message e)))))]
    (h/use-effect (fn [] (-> (folders/facts! server p true) (.then set-facts) (.catch #(set-error (.-message %)))) js/undefined) [])
    [confirm/card {:title (if (= kind :trash) "Move this folder to the Trash?" "Delete this folder now?")
                   :yes (if (= kind :trash) "Move to Trash" "Delete for good")
                   :on-yes go! :on-no on-no}
     [:<> (asked-body p facts error)
      [:p.muted (if (= kind :trash) "The Trash keeps it 7 days; Restore puts it back." "It does not go to the Trash: it cannot be brought back.")]]]))

(defn- buttons
  "kept: why the runner never deletes this folder (the delete buttons off), or nil."
  [{:keys [server p bookmarked? set-ask kept]}]
  [:<>
   [:div.fd-actions
    [:button.fd-btn.primary {:on-click #(rf/dispatch [:folders/use p])} "Use for this chat"]
    [:button.fd-btn {:on-click #(rf/dispatch [:folders/new-chat server p])} "New chat here"]
    [:button.fd-btn {:on-click #(rf/dispatch [:bookmarks/toggle server p])} (if bookmarked? "Unpin" "Pin")]
    [:button.fd-btn.warn {:disabled (boolean kept) :on-click #(set-ask :trash)} "Move to Trash…"]
    [:button.fd-btn.danger {:disabled (boolean kept) :on-click #(set-ask :delete)} "Delete now…"]]
   (when kept [:p.muted.fd-none (str "Claudia never deletes this folder: " kept ".")])])

(defn panel
  "The selected folder p on server. bookmarked?: ★ and Unpin; on-gone: after it went (trash or delete)."
  [{:keys [server p bookmarked? label on-gone]}]
  (let [[facts error] (use-facts server p)
        [ask set-ask] (h/use-state nil)]
    [:div.fd-detail
     [:h2.fd-title (pure/base-name p) (when bookmarked? [:span.fd-star {:title "Bookmarked"} " ★"])]
     [:div.fd-where.mono (str (format/tilde p) " · " label)]
     (when error [:p.fd-warn error])
     [tiles facts]
     [buttons {:server server :p p :bookmarked? bookmarked? :set-ask set-ask :kept (:kept facts)}]
     [chat-list server (:chats facts)]
     (when ask
       [:f> ask-view {:server server :p p :kind ask :on-no #(set-ask nil)
                      :on-done (fn [] (set-ask nil) (on-gone))}])]))
