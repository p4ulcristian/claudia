(ns claudia.web.views.talk
  "Talking mode's controls (README decision 21): the speaker in the chat's header turns it on
  and off for this device; while it is on, each of Claude's replies has a small play button; the
  guard silences the voice when the open chat changes. Shown only when the hub has a voice."
  (:require [claudia.web.speech :as speech]
            [claudia.web.views.pure.talk :as pure]
            [claudia.web.voice :as voice]
            [re-frame.core :as rf]
            [reagent.hooks :as h]))

(defn- use-voice?
  "Whether the hub has a voice (false until known)."
  []
  (let [[ok set-ok] (h/use-state false)]
    (h/use-effect (fn [] (.then (voice/available?) set-ok) nil) [])
    ok))

(def ^:private head-path
  "A person speaking, seen from the side: the head and shoulders."
  "M8 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M2 20c0-3.3 2.7-6 6-6s6 2.7 6 6")

(defn- speaker [on?]
  [:svg {:viewBox "0 0 24 24" :aria-hidden true :fill "none" :stroke "currentColor" :stroke-width 1.8
         :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d head-path}]
   (if on?
     [:path {:d "M15.5 6a4.5 4.5 0 0 1 0 5.5M18.5 3.5a8 8 0 0 1 0 10.5"}]
     [:path {:d "M16 6l5 5M21 6l-5 5"}])])

(defn- toggle-view []
  (let [on? @(rf/subscribe [:talk?])]
    (when (use-voice?)
      [:button.icon.talk-toggle {:type "button" :aria-pressed on? :class (when on? "on")
                                 :aria-label "Talking mode" :data-tip (if on? "Talking mode on: replies are read aloud" "Talking mode off")
                                 :on-click (fn [] (when-not on? (speech/unlock!)) (rf/dispatch [:talk/toggle]))}
       [speaker on?]])))

(defn toggle "The header's talking mode switch." [] [:f> toggle-view])

(defn say-button
  "A reply's play button while talking mode is on."
  [text]
  (when @(rf/subscribe [:talk?])
    (when-let [said (pure/speakable text)]
      [:button.say-reply {:type "button" :aria-label "Read aloud" :data-tip "Read aloud"
                          :on-click (fn [] (speech/unlock!) (speech/say! said))}
       [:svg {:viewBox "0 0 24 24" :aria-hidden true} [:path {:d "M8 5v14l11-7z" :fill "currentColor"}]]])))

(defn- guard-view [k]
  (h/use-effect (fn [] #(speech/stop!)) [k])
  nil)

(defn guard
  "Silences the voice when the chat on screen changes (k: the open chat as a string)."
  [k]
  [:f> guard-view k])
