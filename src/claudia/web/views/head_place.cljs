(ns claudia.web.views.head-place
  "Where the chat works, one pill first in its header's row 2 (README decisions 90, 117): the
  project's logo (the folder icon when it has none), then server › project. Its popup holds both
  picks: the server (a new chat picks where it starts; a started chat's pick opens the move card,
  decision 82; left out when the server cannot change) and the project (the person's bookmarks on
  that server, decision 61, then the folders their chats there use, decision 119, each named by its
  project; a new chat's folder pick, a started chat's folder move), and Other folder…, the File
  Browser to pick any folder."
  (:require [re-frame.core :as rf]
            [claudia.web.format :as format]
            [claudia.web.views.dot :as dot]
            [claudia.web.views.head-pill :as hp]
            [claudia.web.views.pure.head :as pure]))

;; ---- the server ----

(defn- server-section
  "The person's servers, the chat's ticked, an off one dimmed; a pick does pure/server-pick."
  [hc close!]
  (let [rows (pure/server-rows @(rf/subscribe [:server-list]) (:server hc) @(rf/subscribe [:servers]))]
    [:div {:role "group" :aria-label "Server"}
     [:div.prompt-sec (if (:new? hc) "Where this chat starts" "Move this chat to")]
     (for [{n :name :keys [title on? off?]} rows]
       ^{:key n}
       [hp/pop-row {:title title :on? on? :off? off?
                    :pick! (fn [] (close!) (some-> (pure/server-pick hc n) rf/dispatch))}])]))

(defn- server-fixed?
  "Whether the server cannot change here: a read-only header, a new chat with one server, a started
  chat that may not move."
  [{:keys [new? ck] :as hc}]
  (pure/server-passive? hc @(rf/subscribe [:server-list]) (and (not new?) @(rf/subscribe [:can-move? ck]))))

;; ---- the project ----

(defn- shown-folder
  "The folder a chat works in: its own, else (not for a read-only view, whose runner is another
  profile's) its server's work folder once known; nil while unknown."
  [{:keys [server folder ro]}]
  (or (not-empty folder)
      (when-not ro
        (or @(rf/subscribe [:work-folder server])
            (do (rf/dispatch [:folders/load-work server]) nil)))))

(defn- project-row
  "One bookmark of the project popup: its logo (the folder icon without one), name and path."
  [server {f :folder :as r} pick!]
  [:div.where-row
   [:span.where-face [dot/project-face server f [hp/icon :folder]]]
   [hp/pop-row (assoc r :mono? true :pick! #(pick! f))]])

(defn- project-list
  "The rows under their headings: Bookmarks, then Used by your chats."
  [server rows pick!]
  (let [[pinned used] ((juxt filter remove) :pinned? rows)]
    [:<>
     (when (seq pinned) [:div.where-sub "Bookmarks"])
     (for [{f :folder :as r} pinned] ^{:key f} [project-row server r pick!])
     (when (seq used) [:div.where-sub "Used by your chats"])
     (for [{f :folder :as r} used] ^{:key f} [project-row server r pick!])]))

(defn- other-folder
  "Other folder…: the File Browser for this chat (a new chat's Select picks its folder, decision 70)."
  [{:keys [ck]} close!]
  [:button.pop-row.where-other {:type "button" :role "menuitem"
                                :on-click (fn [] (close!) (rf/dispatch [:folders/open {:chat ck}]))}
   [:span.pop-row-name "Other folder…"]
   [:span.pop-row-line "pick any folder in Files"]])

(defn- project-section
  "The server's projects (pure/project-rows), the chat's ticked, then Other folder…; a pick sets a
  new chat's folder or moves a started chat there."
  [{:keys [new? ck server] :as hc} folder close!]
  (let [rows (pure/project-rows @(rf/subscribe [:bookmarks]) @(rf/subscribe [:all-rows]) @(rf/subscribe [:projects])
                                server folder)
        pick! (fn [f] (close!) (rf/dispatch (if new? [:new/settings {:folder f}] [:chat/folder ck f])))]
    [:div {:role "group" :aria-label "Project"}
     [:div.prompt-sec (str "Projects on " @(rf/subscribe [:server-label server]))]
     (if (seq rows)
       [project-list server rows pick!]
       [:div.pop-note "No projects here yet."])
     [:div.pop-sep]
     [other-folder hc close!]]))

(defn- project-value
  "The pill's value: the project's name, else its path; a new chat with none: Pick a project."
  [{:keys [new? folder server]} shown]
  (cond (and new? (empty? folder)) [:span.pill-empty "Pick a project"]
        shown [:bdi (pure/project-value @(rf/subscribe [:bookmarks]) @(rf/subscribe [:projects]) server shown)]
        :else [:span.pill-empty "work folder"]))

(defn- where-value
  "The pill's face: the project's logo (the folder icon without one), then server › project."
  [{:keys [server] :as hc} shown]
  [:<>
   [:span.where-face (if shown [dot/project-face server shown [hp/icon :folder]] [hp/icon :folder])]
   [:span.pill-val.where-val
    [:span.where-server @(rf/subscribe [:server-label server])]
    [:span.where-sep "›"]
    (project-value hc shown)]])

(defn- where-pop
  "Both picks in one popup: the server (when it can change), then the projects on it."
  [hc fixed? shown close!]
  [:div.head-pop.where-pop {:role "menu" :aria-label "Where"}
   (when-not fixed? [:<> [server-section hc close!] [:div.pop-sep]])
   [project-section hc shown close!]])

(defn where-pick
  "The one pill of where the chat works: logo, server › project (README decisions 90, 117)."
  [{:keys [new? folder ro] :as hc}]
  (let [shown (when-not (and new? (empty? folder)) (shown-folder hc))
        fixed? (server-fixed? hc)]
    [:f> hp/popped {:label (str "Where: " @(rf/subscribe [:server-label (:server hc)])
                                (when shown (str " › " (format/tilde shown))))
                    :body [where-value hc shown] :ro ro :class "project-pill"}
     (fn [close!] [where-pop hc fixed? shown close!])]))
