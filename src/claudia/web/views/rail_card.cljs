(ns claudia.web.views.rail-card
  "What a chat and a group say beside their circle on the wide rail (README decision 80), each card
  as tall as a circle's row: the title on one line; the server (with more than one), the project or
  folder and the model; the last reply's line or what it works on; its state (running, waiting for
  answer, done, failed, stopped, new) and how long. And the server's mark on a circle (with more
  than one server), since a hover text never shows on a touch screen."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.rail :as pure]
            [claudia.web.views.pure.sidebar :as sidebar-pure]))

(defn server-badge
  "The server's short mark at the circle's bottom right (claudia.web.views.pure.rail/server-marks)."
  [server]
  [:span.server-badge {:aria-hidden true} @(rf/subscribe [:server-mark server])])

(defn- meta-line
  "The card's second line for a chat on server in folder (project: its name, model: the row's)."
  [{:keys [server folder project model multi?]}]
  (let [{:keys [models defaultModel]} @(rf/subscribe [:meta server])]
    (pure/card-meta {:server-label @(rf/subscribe [:server-label server]) :project project :folder folder
                     :model (pure/model-name models model defaultModel) :multi? multi?})))

(defn- foot
  "The state in words and its time."
  [state when-text unseen?]
  [:span.rc-foot
   ;; is-<state>: plain state names (new, done) are other parts' classes already.
   [:span.rc-state {:class [(str "is-" (name state)) (when unseen? "unseen")]} (pure/state-words state)]
   (when (seq when-text) [:span.rc-when when-text])])

(defn chat-text
  "A chat's card beside its circle (r: its row; unseen?: finished since you looked)."
  [{:keys [title server folder project model] :as r} multi? unseen?]
  (let [now (hooks/use-now)
        state (pure/card-state r)
        [kind line] (sidebar-pure/second-line r)]
    [:span.rail-card
     [:span.rc-title title]
     [:span.rc-meta (meta-line {:server server :folder folder :project (:name project) :model model :multi? multi?})]
     [:span.rc-line {:class kind} (if (str/blank? line) " " line)]
     [foot state (pure/card-when r state now) unseen?]]))

(defn fresh-text
  "The chat being started, as a card: its title, where it starts, new."
  [{:keys [title server folder]} multi?]
  [:span.rail-card
   [:span.rc-title title]
   [:span.rc-meta (meta-line {:server server :folder folder :multi? multi?})]
   [:span.rc-line " "]
   [foot :new "" false]])

(defn group-text
  "A group's card beside its stack: its name, how many chats, their titles, its most pressing state."
  [group-name rows]
  (let [state (pure/group-state rows) n (count rows)]
    [:span.rail-card
     [:span.rc-title group-name]
     [:span.rc-meta (str n " chat" (when (not= 1 n) "s"))]
     [:span.rc-line (str/join " · " (map :title rows))]
     [foot state "" false]]))
