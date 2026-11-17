(ns claudia.web.views.thumb-edge
  "The right edge of a touch tablet (README decision 40): a big tablet held in two hands is held at
  its sides partway up, so the right thumb rests on the right edge at middle height. There: ‹ (the
  previous picture), the mic and › (the next picture) in a row, left going left and right going
  right; over them search (the chats) and archive (the open chat, asked first; unarchive for an
  archived one). Each at least 12 mm, all see-through at rest.
  The composer leaves its own mic out on these screens (claudia.web.views.chat). The ‹ › show while
  the picture is unfolded and has more than one picture; the mic wherever the composer shows;
  archive while a chat is open."
  (:require [claudia.web.search-open :as search-open]
            [re-frame.core :as rf]
            [claudia.web.views.head :as head]
            [claudia.web.views.mic :as mic]))

(defn- step-button [label dir disabled?]
  [:button.edge-step {:type "button" :disabled disabled? :aria-label label :data-tip label
                      :on-click #(rf/dispatch [:picture/step dir])}
   (if (neg? dir) "‹" "›")])

(defn- search-button []
  [:button.edge-step.edge-icon {:type "button" :aria-label "Search chats" :data-tip "Search chats"
                                :on-click search-open/open!}
   [:svg {:width 28 :height 28 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linecap "round" :aria-hidden true}
    [:circle {:cx 11 :cy 11 :r 6}] [:path {:d "M20 20l-4.5-4.5"}]]])

(defn- archive-button
  "Archive the open chat (asked first, as the header's archive), or unarchive an archived one."
  [[server chat :as ck]]
  (let [out? (boolean (get-in @(rf/subscribe [:servers]) [server :rows chat :archived]))
        label (if out? "Unarchive chat" "Archive chat")]
    [:button.edge-step.edge-icon {:type "button" :aria-label label :data-tip label
                                  :on-click #(rf/dispatch (if out? [:chat/archive ck false] [:archive/ask ck]))}
     [head/archive-icon out?]]))

(defn- composer-shown?
  "The composer shows (claudia.web.views.chat/pane-foot): not read-only, and a server to chat on."
  []
  (let [me @(rf/subscribe [:me])]
    (and (not @(rf/subscribe [:read-only?])) (seq (:servers me)))))

(defn- top-row
  "Search and archive, over the mic."
  [oc]
  [:div.edge-row
   [search-button]
   (when oc [archive-button (:ck oc)])])

(defn- middle-row
  "‹ the mic ›: left goes left, right goes right; an empty slot keeps the mic in the middle."
  [oc steps? index n]
  [:div.edge-row
   (if steps? (step-button "Previous picture" -1 (<= index 0)) [:span.edge-gap])
   (if (composer-shown?) [:f> mic/button (if oc (:ck oc) :new)] [:span.edge-gap])
   (if steps? (step-button "Next picture" 1 (>= index (dec n))) [:span.edge-gap])])

(defn edge []
  (let [oc @(rf/subscribe [:open-chat])
        {:keys [pictures index]} @(rf/subscribe [:picture-history])
        n (count pictures)
        steps? (and (not (:folded? @(rf/subscribe [:fold-state]))) (> n 1))]
    [:div.thumb-edge
     [top-row oc]
     [middle-row oc steps? index n]]))
