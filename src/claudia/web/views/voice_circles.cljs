(ns claudia.web.views.voice-circles
  "The chats' voice messages as circles round the mic (README decisions 93 and 94): one per chat with
  messages waiting, in its colour, the count on it when more than one; round a floating mic a full
  ring, else a fan above it. Each is a little mic (the mic's own press, drag and let go, hands): a
  tap plays that chat's messages, on the one playing the next; a hold opens the mic's flower round
  it and talks to that chat. While one plays or is held the others step away, and while the mic is
  held all do (the held one stays, unseen, to keep the finger). The one playing wears the bars that
  move (dots while its sound is made), not the big mic, which stays the open chat's (README decision
  105); held, its left petal skips all of its waiting messages."
  (:require [claudia.shared.colours :as colours]
            [claudia.web.hooks :as hooks]
            [claudia.web.views.pure.voicemail :as pure]
            [claudia.web.views.pure.words :as words]
            [re-frame.core :as rf]))

(def speaker-bars
  "The bars on what reads a reply aloud (they move by CSS)."
  [:span.mic-speak-bars {:aria-hidden true} [:i] [:i] [:i] [:i] [:i]])

(def speaker-loading
  "On what reads a reply whose sound is still being made: three dots in turn (they move by CSS)."
  [:span.mic-speak-loading {:aria-hidden true} [:i] [:i] [:i]])

(defn- label
  "A circle's words for its tip and screen readers."
  [{:keys [title color]} n]
  (str (or (colours/name-of color) "A chat") ": " (or (not-empty title) "chat") ", " n " voice message"
       (when (> n 1) "s") " (tap to play, tap again for the next, hold to talk to it, hold and drag left to skip all)"))

(defn- face
  "What a circle shows: the moving bars (dots while loading) on the one playing, else its count."
  [k n {:keys [speaking loading]}]
  (cond
    (and (= k speaking) loading) speaker-loading
    (= k speaking) speaker-bars
    (> n 1) [:span n]))

(defn- circle-class
  "Playing, held (kept but unseen) or away (another one plays or is held, or the mic is held)."
  [k {:keys [held away speaking]}]
  (cond
    (= k held) "held"
    (= k speaking) "playing"
    (or away held speaking) "away"))

(defn- circle [{:keys [k msgs]} {:keys [x y] :as spot} row {:keys [hands] :as state}]
  (let [d (pure/size msgs) n (count msgs)]
    [:button.voice-circle {:type "button" :class (circle-class k state)
                           :style {:left (str "calc(50% + " x "px)") :top (str "calc(50% + " y "px)")
                                   :width d :height d "--chat" (words/colour-var (:color row))}
                           :aria-label (label row n) :data-tip (label row n)
                           :on-pointer-down #((:down hands) % {:k k :at spot})
                           :on-pointer-move (:move hands)
                           :on-pointer-up (:up hands)
                           :on-pointer-cancel (:up hands)
                           :on-context-menu #(.preventDefault %)}
     (face k n state)]))

(defn circles
  "The circles round the mic (a function component: [:f> circles opts]). opts: :side (:ring round a
  floating mic, :up above it), :hands {:down (fn [e target]) :move :up}, :held (the chat whose
  circle is held), :away (the mic is held)."
  [{:keys [side] :as opts}]
  (let [cs (take (pure/max-for side) (pure/circles @(rf/subscribe [:voice-msgs]) (hooks/use-now)))
        {speaking :k loading :loading} @(rf/subscribe [:speaking])
        state (assoc opts :speaking (when (some #(= speaking (:k %)) cs) speaking) :loading loading)
        servers @(rf/subscribe [:servers])]
    (when (seq cs)
      (into [:div.voice-circles]
            (map (fn [{[server chat] :k :as c} spot]
                   ^{:key (str server "/" chat)} [circle c spot (get-in servers [server :rows chat]) state])
                 cs (pure/spots (count cs) side))))))
