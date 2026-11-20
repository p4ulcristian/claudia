(ns claudia.web.views.talk
  "The chats' voices (README decision 21): the voice pill's popup in the chat's header (Off or a
  named voice, each with a sample to hear; kept with the chat) and each reply's play button while
  the chat has a voice. Shown only when the hub has a voice."
  (:require [claudia.shared.voices :as voices]
            [claudia.web.speech :as speech]
            [claudia.web.views.pure.talk :as pure]
            [claudia.web.voice :as voice]
            [re-frame.core :as rf]
            [reagent.hooks :as h]))

(defn use-voice?
  "Whether the hub has a voice (false until known)."
  []
  (let [[ok set-ok] (h/use-state false)]
    (h/use-effect (fn [] (.then (voice/available?) set-ok) nil) [])
    ok))

(defn voice-name
  "The pill's value for a voice id: its name, or Off."
  [id]
  (or (:name (voices/by-id id)) "Off"))

(def ^:private sample "Hi, this is how I sound when I read your chat's replies.")

(defn- hear! [{:keys [id]}]
  (speech/unlock!)
  (speech/say-now! {:id (str "sample-" id "-" (js/Date.now)) :k nil :voice id :text sample}))

(defn- option [label line sel? pick!]
  [:button.prompt-opt {:type "button" :class (when sel? "sel") :on-click pick!}
   [:span.dot] [:span [:span.nm label] (when (seq line) [:small line])]])

(defn- voice-row [v picked choose!]
  [:div.voice-opt
   [option (:name v) (:description v) (= picked (:id v)) #(choose! (:id v))]
   [:button.voice-hear {:type "button" :aria-label (str "Hear " (:name v)) :data-tip (str "Hear " (:name v))
                        :on-click #(hear! v)}
    [:svg {:viewBox "0 0 24 24" :aria-hidden true} [:path {:d "M8 5v14l11-7z" :fill "currentColor"}]]]])

(defn voice-pop
  "The voice pill's popup for chat k (nil: the new chat): Off, then every voice with its sample. A
  pick closes it."
  [k picked close!]
  (let [choose! (fn [id] (close!) (speech/unlock!) (rf/dispatch [:chat/switches k {:voice id}]))]
    [:div.head-pop.voice-pop {:role "dialog" :aria-label "Voice"}
     [:div.prompt-sec "Reads this chat's replies"]
     [option "Off" "this chat stays silent" (= "" picked) #(choose! "")]
     (for [v voices/all]
       ^{:key (:id v)} [voice-row v picked choose!])]))

(defn say-button
  "A reply's play button while the chat k has a voice: reads it now, in that voice."
  [k text]
  (let [v @(rf/subscribe [:voice k])]
    (when (seq v)
      (when-let [said (pure/speakable text)]
        [:button.say-reply {:type "button" :aria-label "Read aloud" :data-tip "Read aloud"
                            :on-click (fn [] (speech/unlock!)
                                        (speech/say-now! {:id (str "again-" (hash said)) :k k :voice v :text said}))}
         [:svg {:viewBox "0 0 24 24" :aria-hidden true} [:path {:d "M8 5v14l11-7z" :fill "currentColor"}]]]))))
