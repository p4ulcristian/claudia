(ns claudia.web.views.head-place
  "Where the chat works, the first two pills of its header's row 2 (README decision 90): the server
  (a new chat picks where it starts; a started chat's pick opens the move card, decision 82) and the
  project (the person's bookmarks on that server, decision 61: a new chat's folder pick, a started
  chat's folder move)."
  (:require [re-frame.core :as rf]
            [claudia.web.format :as format]
            [claudia.web.views.head-pill :as hp]
            [claudia.web.views.pure.head :as pure]))

;; ---- the server ----

(defn- server-pop
  "The person's servers, the chat's ticked, an off one dimmed; a pick does pure/server-pick."
  [hc close!]
  (let [rows (pure/server-rows @(rf/subscribe [:server-list]) (:server hc) @(rf/subscribe [:servers]))]
    [:div.head-pop {:role "menu" :aria-label "Server"}
     [:div.prompt-sec (if (:new? hc) "Where this chat starts" "Move this chat to")]
     (for [{n :name :keys [title on? off?]} rows]
       ^{:key n}
       [hp/pop-row {:title title :on? on? :off? off?
                    :pick! (fn [] (close!) (some-> (pure/server-pick hc n) rf/dispatch))}])]))

(defn server-pick
  "The server pill: the computer icon and the server's label. Passive for a read-only header, a new
  chat with one server, a started chat that may not move."
  [{:keys [new? ck server] :as hc}]
  (let [servers @(rf/subscribe [:server-list])
        can-move? (and (not new?) @(rf/subscribe [:can-move? ck]))]
    [:f> hp/popped {:k :server :label "Server" :value @(rf/subscribe [:server-label server])
                    :ro (pure/server-passive? hc servers can-move?)}
     (fn [close!] [server-pop hc close!])]))

;; ---- the project ----

(defn- shown-folder
  "The folder a chat works in: its own, else (not for a read-only view, whose runner is another
  profile's) its server's work folder once known; nil while unknown."
  [{:keys [server folder ro]}]
  (or (not-empty folder)
      (when-not ro
        (or @(rf/subscribe [:work-folder server])
            (do (rf/dispatch [:folders/load-work server]) nil)))))

(defn- project-pop
  "The server's bookmarks, each its name and path, the chat's ticked; a pick sets a new chat's folder
  or moves a started chat there."
  [{:keys [new? ck server]} folder close!]
  (let [rows (pure/project-rows @(rf/subscribe [:bookmarks]) server folder)
        pick! (fn [f] (close!) (rf/dispatch (if new? [:new/settings {:folder f}] [:chat/folder ck f])))]
    [:div.head-pop {:role "menu" :aria-label "Project"}
     [:div.prompt-sec (str "Projects on " @(rf/subscribe [:server-label server]))]
     (if (seq rows)
       (for [{f :folder :as r} rows]
         ^{:key f} [hp/pop-row (assoc r :mono? true :pick! #(pick! f))])
       [:div.pop-note "No projects here yet: pin a folder in Files."])]))

(defn- project-value
  "The pill's value: the project's name, else its path; a new chat with none: Pick a project."
  [{:keys [new? folder server]} shown]
  (cond (and new? (empty? folder)) [:span.pill-empty "Pick a project"]
        shown [:bdi (pure/project-value @(rf/subscribe [:bookmarks]) server shown)]
        :else [:span.pill-empty "work folder"]))

(defn project-pick
  "The project pill: the folder icon and the project's name (README decision 90)."
  [{:keys [new? folder ro] :as hc}]
  (let [shown (when-not (and new? (empty? folder)) (shown-folder hc))]
    [:f> hp/popped {:k :folder :label (str "Project" (when shown (str ": " (format/tilde shown))))
                    :value (project-value hc shown) :ro ro :class "project-pill"}
     (fn [close!] [project-pop hc shown close!])]))
