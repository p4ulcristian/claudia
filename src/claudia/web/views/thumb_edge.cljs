(ns claudia.web.views.thumb-edge
  "The right edge of a touch tablet (README decision 40): a big tablet held in two hands is held at
  its sides partway up, so the right thumb rests on the right edge at middle height. There: Stop
  (the open chat's running turn) left of the mic and Settings right of it; over them search (the chats), My words (the panel
  of claudia.web.views.my-words: see, add and remove them) and archive (the open chat, asked first;
  unarchive for an archived one); under them ‹ (the previous picture), New chat and › (the next
  picture), left going left and right going right (the rail has no + any more). Each at least
  12 mm, all see-through at rest.
  The composer leaves its own mic out on these screens (claudia.web.views.chat). Stop shows while
  the open chat's turn runs; the ‹ › while the picture is unfolded and has more than one picture;
  the mic wherever the composer shows; My words where the composer shows and the hub has a listen
  service; archive while a chat is open; New chat where a new chat can be written in."
  (:require [claudia.web.search-open :as search-open]
            [re-frame.core :as rf]
            [reagent.core :as r]
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

(defn- words-button []
  [:button.edge-step.edge-icon {:type "button" :aria-label "My words" :data-tip "My words"
                                :on-click #(rf/dispatch [:words/panel true])}
   [:svg {:width 28 :height 28 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
    [:path {:d "M5 4.5A1.5 1.5 0 0 1 6.5 3H19v15H6.5A1.5 1.5 0 0 0 5 19.5z"}]
    [:path {:d "M5 19.5A1.5 1.5 0 0 0 6.5 21H19v-3"}] [:path {:d "M9 7.5h6"}]]])

(defn- new-button []
  [:button.edge-step.edge-icon {:type "button" :aria-label "New chat" :data-tip "New chat (Alt+N)"
                                :on-click #(rf/dispatch [:nav/new])}
   [:svg {:width 28 :height 28 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linecap "round" :aria-hidden true}
    [:path {:d "M12 5v14M5 12h14"}]]])

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

(defn- words-shown?
  "My words: where the composer shows and the hub has a listen service (claudia.web.words)."
  []
  (let [m @(rf/subscribe [:my-words])]
    (boolean (and (composer-shown?) (some? m) (not (:off m))))))

(defn- top-row
  "Search, My words and archive, over the mic."
  [oc]
  [:div.edge-row
   [search-button]
   (when (words-shown?) [words-button])
   (when oc [archive-button (:ck oc)])])

(defn- stop-button
  "Stops the open chat's running turn (as the composer's Stop and Esc)."
  []
  [:button.edge-step.edge-icon {:type "button" :aria-label "Stop" :data-tip "Stop (Esc)"
                                :on-click #(rf/dispatch [:chat/stop])}
   [:svg {:width 24 :height 24 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linejoin "round" :aria-hidden true}
    [:rect {:x 6 :y 6 :width 12 :height 12 :rx 2}]]])

(defn- settings-button
  "Opens Settings (in the drawer, as the menu's Settings)."
  []
  [:button.edge-step.edge-icon {:type "button" :aria-label "Settings" :data-tip "Settings"
                                :on-click #(do (rf/dispatch [:sidebar/toggle true])
                                               (rf/dispatch [:ops/settings true]))}
   [:svg {:width 26 :height 26 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
    [:circle {:cx 12 :cy 12 :r 3}]
    [:path {:d "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"}]]])

(defn- middle-row
  "Stop, the mic, Settings: an empty slot keeps the mic in the middle."
  [oc]
  (let [running (when oc (:running @(rf/subscribe [:chat (:ck oc)])))]
    [:div.edge-row
     (if running [stop-button] [:span.edge-gap])
     (if (composer-shown?) [:f> mic/button (if oc (:ck oc) :new)] [:span.edge-gap])
     [settings-button]]))

(defn- bottom-row
  "‹ New chat ›, under the mic: left goes left, right goes right."
  [steps? index n]
  [:div.edge-row
   (if steps? (step-button "Previous picture" -1 (<= index 0)) [:span.edge-gap])
   (if @(rf/subscribe [:new-chat-read-only?]) [:span.edge-gap] [new-button])
   (if steps? (step-button "Next picture" 1 (>= index (dec n))) [:span.edge-gap])])

(defn- edge-view []
  (let [oc @(rf/subscribe [:open-chat])
        {:keys [pictures index]} @(rf/subscribe [:picture-history])
        n (count pictures)
        steps? (and (not (:folded? @(rf/subscribe [:fold-state]))) (> n 1))]
    [:div.thumb-edge
     [top-row oc]
     [middle-row oc]
     [bottom-row steps? index n]]))

(defn edge
  "The edge; mounting it asks the hub whether it has a listen service (for My words)."
  []
  (r/with-let [_ (rf/dispatch [:words/load])]
    [edge-view]))
