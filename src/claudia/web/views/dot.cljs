(ns claudia.web.views.dot
  "A chat's circle, the same on the rail and in the chat list: the project's logo big (else the
  title's first letter), the title's letter small beside a logo, and its state badge (? waiting on
  you, working dots, ✓ done since you looked)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.chatops :as ops]))

(defn state-badge [{:keys [waiting running unseen]} read-only?]
  (cond
    waiting [:span.state-badge.waiting {:title "Waiting for you"} "?"]
    running [:span.state-badge.thinking {:title "Working"} [:i] [:i] [:i]]
    (and unseen (not read-only?)) [:span.state-badge.done {:title "Done"} "✓"]))

(defn- letter [title] (str/upper-case (subs (str (if (str/blank? title) "·" title)) 0 1)))

(defn chat-dot
  "row: a list row with :server."
  [{:keys [server folder title] :as row}]
  (let [broken (r/atom false)]
    (fn [{:keys [server folder title] :as row}]
      (let [project @(rf/subscribe [:project server folder])
            view @(rf/subscribe [:view])
            logo? (and (:logo project) (not @broken))]
        (when (and (seq folder) (nil? project)) (rf/dispatch [:ops/project server folder]))
        [:span.rail-dot {:class (when logo? "has-logo")}
         (if logo?
           [:<>
            [:img.rail-logo {:src (ops/logo-url view server folder) :alt "" :on-error #(reset! broken true)}]
            [:span.rail-letter (letter title)]]
           (letter title))
         [state-badge row (some? view)]]))))
