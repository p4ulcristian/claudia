(ns claudia.web.views.thumb-edge
  "The right edge of a touch tablet (README decision 40): a big tablet held in two hands is held at
  its sides partway up, so the right thumb rests on the right edge at middle height. There: ‹ (the
  previous picture), the mic, › (the next picture), each at least 12 mm. The composer leaves its own
  mic out on these screens (claudia.web.views.chat). The ‹ › show while the picture is unfolded and
  has more than one picture; the mic wherever the composer shows."
  (:require [re-frame.core :as rf]
            [claudia.web.views.mic :as mic]))

(defn- step-button [label dir disabled?]
  [:button.edge-step {:type "button" :disabled disabled? :aria-label label :data-tip label
                      :on-click #(rf/dispatch [:picture/step dir])}
   (if (neg? dir) "‹" "›")])

(defn- composer-shown?
  "The composer shows (claudia.web.views.chat/pane-foot): not read-only, and a server to chat on."
  []
  (let [me @(rf/subscribe [:me])]
    (and (not @(rf/subscribe [:read-only?])) (seq (:servers me)))))

(defn edge []
  (let [oc @(rf/subscribe [:open-chat])
        {:keys [pictures index]} @(rf/subscribe [:picture-history])
        n (count pictures)
        steps? (and (not (:folded? @(rf/subscribe [:fold-state]))) (> n 1))]
    [:div.thumb-edge
     (when steps? (step-button "Previous picture" -1 (<= index 0)))
     (when (composer-shown?) [:f> mic/button (if oc (:ck oc) :new)])
     (when steps? (step-button "Next picture" 1 (>= index (dec n))))]))
