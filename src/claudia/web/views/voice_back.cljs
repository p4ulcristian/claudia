(ns claudia.web.views.voice-back
  "On a sent dictated message, while its clips are kept (30 minutes): ▶ plays the voice as
  recorded, and Heard as shows each clip's words as the speech model heard them and after the
  cleanup, so a wrong word is placed: the voice, the hearing, or the cleanup."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [claudia.web.voice-back :as voice]
            [claudia.web.views.pure.voice-back :as pure]))

(defn- heard-list [clips]
  [:ol.vb-heard
   (for [[i {:keys [secs raw text cleaned?]}] (map-indexed vector (pure/heard-lines clips))]
     ^{:key i}
     [:li [:span.muted.small (str (js/Math.round secs) " s · heard")] " " raw
      (when cleaned? [:<> [:br] [:span.muted.small "cleaned"] " " text])])])

(defn- player [_msg _clips]
  (let [open? (r/atom false)]
    (fn [msg clips]
      (let [on? (= msg (:msg @voice/playing))]
        [:div.vb
         [:button.pic-link {:title "Your voice as recorded (kept on this device for 30 minutes)"
                            :on-click #(voice/toggle! msg clips)}
          (str (if on? "■ stop" "▶ your voice") " · " (pure/total-secs clips) " s")]
         [:button.pic-link {:on-click #(swap! open? not)} (if @open? "hide heard as" "heard as")]
         (when @open? [heard-list clips])]))))

(defn listen-back
  "The listen-back row for sent message msg, or nothing when no clip of it is kept."
  [msg]
  (when-let [clips @(rf/subscribe [:voice-back/of msg])]
    [player msg clips]))
