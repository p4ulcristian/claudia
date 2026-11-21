(ns claudia.web.views.notify
  "The notify pill's popup in the chat's header (README decision 90): each sound with a ▶ that plays
  it once (it picks nothing), then Off. A sound picked turns the chat's bell on with it; Off is the
  bell off: no chime, no popup, no phone push (decision 21)."
  (:require [claudia.shared.sounds :as sounds]
            [claudia.web.alerts :as alerts]
            [claudia.web.views.head-pill :as hp]))

(defn- sound-row [{id :id n :name} picked color choose!]
  [:div.voice-opt
   [hp/pop-row {:title n :on? (= picked id) :pick! #(choose! id)}]
   [:button.voice-hear {:type "button" :aria-label (str "Hear " n) :data-tip (str "Hear " n)
                        :on-click #(alerts/play! :done color id)}
    [:svg {:viewBox "0 0 24 24" :aria-hidden true} [:path {:d "M8 5v14l11-7z" :fill "currentColor"}]]]])

(defn notify-pop
  "The popup for chat k (nil: the new chat) in colour color, picked: its sound id, \"\" Off. A pick
  closes it."
  [k picked color close!]
  (let [choose! (fn [id] (close!) (alerts/pick-sound! k id color))]
    [:div.head-pop.notify-pop {:role "menu" :aria-label "Notify"}
     [:div.prompt-sec "When it finishes or waits on you"]
     (for [s sounds/all] ^{:key (:id s)} [sound-row s picked color choose!])
     [:div.pop-sep]
     [hp/pop-row {:title "Off" :line "no sound, no popup, no phone push" :on? (= "" picked)
                  :pick! #(choose! "")}]]))
