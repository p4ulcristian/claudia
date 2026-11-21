(ns claudia.web.views.voice-circles
  "The chats' voice messages as circles round the mic (README decision 90): one per chat with
  messages waiting, in its colour, the chat waiting longest in the middle, the count on it when more
  than one. A tap plays that chat's messages (tap the one playing: it stops); a swipe takes them
  away unheard (gone on every device); a long press opens the chat. Hidden while the mic is held."
  (:require [claudia.shared.colours :as colours]
            [claudia.web.hooks :as hooks]
            [claudia.web.speech :as speech]
            [claudia.web.views.pure.voicemail :as pure]
            [claudia.web.views.pure.words :as words]
            [re-frame.core :as rf]))

;; The finger on a circle: {:x :y :t} where and when it went down.
(defonce ^:private press (atom nil))

(defn- down! [^js e]
  (when (zero? (.-button e))
    (.preventDefault e)
    (.setPointerCapture (.-currentTarget e) (.-pointerId e))
    (reset! press {:x (.-clientX e) :y (.-clientY e) :t (js/performance.now)})))

(defn- act!
  "What a finger let go on chat k's circle does (pure/gesture)."
  [gesture k msgs playing?]
  (case gesture
    :dismiss (do (when playing? (speech/stop!)) (rf/dispatch [:voice/heard k (mapv :id msgs)]))
    :open (rf/dispatch [:nav/open k])
    :play (if playing? (speech/stop!) (do (speech/unlock!) (rf/dispatch [:voice/play k])))))

(defn- up! [^js e k msgs playing?]
  (when-let [{:keys [x y t]} @press]
    (reset! press nil)
    (act! (pure/gesture (- (.-clientX e) x) (- (.-clientY e) y) (- (js/performance.now) t)) k msgs playing?)))

(defn- label
  "A circle's words for its tip and screen readers."
  [{:keys [title color]} n]
  (str (or (colours/name-of color) "A chat") ": " (or (not-empty title) "chat") ", " n " voice message"
       (when (> n 1) "s") " (tap to play, swipe away, hold to open)"))

(defn- circle [{:keys [k msgs]} {:keys [x y]} row playing?]
  (let [d (pure/size msgs) n (count msgs)]
    [:button.voice-circle {:type "button" :class (when playing? "playing")
                           :style {:left (str "calc(50% + " x "px)") :top (str "calc(50% + " y "px)")
                                   :width d :height d "--chat" (words/colour-var (:color row))}
                           :aria-label (label row n) :data-tip (label row n)
                           :on-pointer-down down!
                           :on-pointer-up #(up! % k msgs playing?)
                           :on-pointer-cancel #(reset! press nil)
                           :on-context-menu #(.preventDefault %)}
     (when (> n 1) [:span n])]))

(defn circles
  "The circles round the mic (a function component: [:f> circles side]); side :left on the tablet's
  rail (they fan out to its left), else above it."
  [side]
  (let [cs (pure/circles @(rf/subscribe [:voice-msgs]) (hooks/use-now))
        speaking (:k @(rf/subscribe [:speaking]))
        servers @(rf/subscribe [:servers])]
    (when (seq cs)
      (into [:div.voice-circles]
            (map (fn [{[server chat :as k] :k :as c} spot]
                   ^{:key (str server "/" chat)} [circle c spot (get-in servers [server :rows chat]) (= k speaking)])
                 cs (pure/spots (count cs) side))))))
