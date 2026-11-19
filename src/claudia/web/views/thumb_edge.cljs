(ns claudia.web.views.thumb-edge
  "The right edge of a touch tablet (README decisions 40, 73): a big tablet held in two hands is held
  at its sides partway up, so the right thumb rests on the right edge at middle height. There a rail
  on the right wall, one column with the mic in its middle: over it search (the chats), My words
  (its view in the right half, claudia.web.views.my-words: see, add and remove them), archive (the open chat,
  asked first; unarchive for an archived one) and Stop (the open chat's running turn); under it
  New chat, then ‹ (the previous picture) and › (the next picture). At its bottom the foot the
  chats' rail had: the update pill, your profile circle (its menu holds Settings) and the version's
  line, so the chats have the left edge to themselves. Each at least 12 mm, all see-through at rest.
  The mic can be dragged out of the rail (claudia.web.views.mic).
  The composer leaves its own mic out on these screens (claudia.web.views.chat). Stop shows while
  the open chat's turn runs; the ‹ › while the picture is unfolded and has more than one picture;
  the mic wherever the composer shows; My words where the composer shows and the hub has a listen
  service; archive while a chat is open; New chat where a new chat can be written in."
  (:require [claudia.web.search-open :as search-open]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.views.head :as head]
            [claudia.web.views.menu :as menu]
            [claudia.web.views.mic :as mic]
            [claudia.web.views.update-pill :as update-pill]))

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
                                :on-click #(rf/dispatch [:right/toggle :words])}
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

(defn- stop-button
  "Stops the open chat's running turn (as the composer's Stop and Esc)."
  []
  [:button.edge-step.edge-icon {:type "button" :aria-label "Stop" :data-tip "Stop (Esc)"
                                :on-click #(rf/dispatch [:chat/stop])}
   [:svg {:width 24 :height 24 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 2
          :stroke-linejoin "round" :aria-hidden true}
    [:rect {:x 6 :y 6 :width 12 :height 12 :rx 2}]]])

(defn- over-mic
  "Search, My words, archive, Stop: over the mic, ending at it."
  [oc]
  (let [running (when oc (:running @(rf/subscribe [:chat (:ck oc)])))]
    [:div.edge-over
     [search-button]
     (when (words-shown?) [words-button])
     (when oc [archive-button (:ck oc)])
     (when running [stop-button])]))

(defn- foot
  "The rail's foot: the update pill, your profile circle (whose chats, the menu with Settings) and the
  version's line, at the bottom, off the chats' rail (README decision 73)."
  []
  [:div.edge-foot
   [update-pill/pill]
   [:f> menu/button]
   [update-pill/updated]])

(defn- under-mic
  "New chat, then ‹ and ›: under the mic, starting at it; the foot at the bottom."
  [steps? index n]
  [:div.edge-under
   (when-not @(rf/subscribe [:new-chat-read-only?]) [new-button])
   (when steps? (step-button "Previous picture" -1 (<= index 0)))
   (when steps? (step-button "Next picture" 1 (>= index (dec n))))
   [foot]])

(defn- edge-view []
  (let [oc @(rf/subscribe [:open-chat])
        {:keys [pictures index]} @(rf/subscribe [:picture-history])
        n (count pictures)
        steps? (and (not (:folded? @(rf/subscribe [:fold-state]))) (> n 1))]
    [:div.thumb-edge
     [over-mic oc]
     (if (composer-shown?) [:f> mic/button (if oc (:ck oc) :new) {:rail? true}] [:span.edge-gap])
     [under-mic steps? index n]]))

(defn edge
  "The edge; mounting it asks the hub whether it has a listen service (for My words)."
  []
  (r/with-let [_ (rf/dispatch [:words/load])]
    [edge-view]))
