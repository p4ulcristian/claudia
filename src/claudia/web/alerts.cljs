(ns claudia.web.alerts
  "Sounds and desktop popups when a chat finishes a turn or starts waiting on you (a question).
  Each chat has its own bell (README decision 21, kept with the chat, on unless turned off): with it
  off, that chat makes no sound, no popup and no phone push. Popups are also a per-device switch
  (the browser's permission). The open chat counts only while the window is not in front.

  Sounds are made here with Web Audio (no files): each chat colour has its own note, so you hear
  which chat it was. A finished turn rings its note once like a small bell; a chat waiting on you
  asks with two notes going up. Popups go through the browser's notifications (turning them on asks
  its permission once), one per chat at a time, silent; a click brings the window up and opens the
  chat."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.core :as r]
            [claudia.shared.colours :as colours]
            [claudia.web.rowdiff :as rowdiff]
            [claudia.web.views.pure.switches :as switches]))

;; The chat colours in hue order (claudia.css --c-*): neighbouring colours get neighbouring notes.
(def ^:private colours (conj colours/chat-colours "ash"))

;; A major pentatonic from C4 up: any two notes sound fine together. Index 0 = no colour.
(def ^:private steps [0 2 4 7 9])
(defn- midi [i] (+ 60 (* 12 (quot i (count steps))) (steps (mod i (count steps)))))
(defn- hz [m] (* 440 (js/Math.pow 2 (/ (- m 69) 12))))
(defn- note-of [color] (midi (if-let [i (first (keep-indexed #(when (= %2 color) %1) colours))] (inc i) 0)))

(defonce ^:private ctx (atom nil))
(defn- audio [] (or @ctx (reset! ctx (js/AudioContext.))))

(defn- bell
  "A soft bell: a sine with a faint octave above, a gentle start and a fade."
  [m at gain length]
  (let [^js a (audio)
        out (.createGain a)]
    (.setValueAtTime (.-gain out) 0 at)
    (.linearRampToValueAtTime (.-gain out) gain (+ at 0.03))
    (.exponentialRampToValueAtTime (.-gain out) 0.0001 (+ at length))
    (.connect out (.-destination a))
    (doseq [[mult g] [[1 1] [2 0.12]]]
      (let [o (.createOscillator a)
            og (.createGain a)]
        (set! (.-type o) "sine")
        (set! (.. o -frequency -value) (* mult (hz m)))
        (set! (.. og -gain -value) g)
        (.connect (.connect o og) out)
        (.start o at)
        (.stop o (+ at length 0.05))))))

(defn play!
  "kind: :done or :waiting; color: the chat's colour name."
  [kind color]
  (let [^js a (audio)
        m (note-of color)
        t (+ (.-currentTime a) 0.02)]
    (when (= "suspended" (.-state a)) (.resume a))
    (if (= kind :done)
      (bell m t 0.045 0.9)
      (do (bell m t 0.035 0.5)
          (bell (+ m 4) (+ t 0.16) 0.035 0.8)))))

;; ---- popups ----

(defn- notifications? [] (exists? js/Notification))
(defn popups-allowed? [] (and (notifications?) (= "granted" (.-permission js/Notification))))

(defn- allow-popups!
  "Asks the browser once; a promise of whether popups may show."
  []
  (cond
    (not (notifications?)) (js/Promise.resolve false)
    (= "default" (.-permission js/Notification)) (.then (.requestPermission js/Notification) #(popups-allowed?))
    :else (js/Promise.resolve (popups-allowed?))))

(defonce on (r/atom {:popups (and (= "on" (js/localStorage.getItem "claudia.popups")) (popups-allowed?))}))

(defn notify!
  "A desktop popup (when popups are on and allowed): title, body; tag: one popup per tag at a time;
  a click brings the window up, opens the chat [server id] and closes it."
  [title body tag [server id]]
  (when (and (:popups @on) (popups-allowed?))
    (let [n (js/Notification. title #js {:body body :tag tag :icon "/app-icon/icon-192.png" :silent true})]
      (set! (.-onclick n) (fn []
                            (js/window.focus)
                            (rf/dispatch [:nav/open [server id]])
                            (.close n))))))

(defn- popup! [kind server {:keys [id title]}]
  (notify! (if (seq title) title "Chat") (if (= kind :done) "Finished" "Waiting for you")
           (str server "/" id) [server id]))

;; ---- the toggles ----

(defn- set-popups! [v]
  (-> (if v (allow-popups!) (js/Promise.resolve false))
      (.then (fn [allowed]
               (swap! on assoc :popups allowed)
               (js/localStorage.setItem "claudia.popups" (if allowed "on" "off"))))))

(defn- switch-row [value on-change label note]
  [:label.st-row.st-switch
   [:span.st-label label (when note [:span.st-note note])]
   [:input {:type "checkbox" :role "switch" :checked (boolean value)
            :on-change #(on-change (.. % -target -checked))}]
   [:span.st-knob]])

(defn popups-switch
  "The Desktop popups switch (the settings menu)."
  []
  [switch-row (:popups @on) set-popups! "Desktop popups" "when a chat finishes or waits on you"])

(def ^:private bell-path "M6 9.5a6 6 0 0 1 12 0c0 4.6 1.8 6.2 1.8 6.2H4.2S6 14.1 6 9.5M10 19a2 2 0 0 0 4 0")

(defn- ring-bell!
  "The bell of chat k ([server id]; nil: the new chat) turned to on?; turning it on rings its note once."
  [k on? color]
  (rf/dispatch [:chat/switches k {:bell on?}])
  (when on? (play! :done color)))

(defn bell-button
  "The chat header's bell for the chat k ([server id]; nil: the new chat) in colour color: its chime,
  popup and phone push when it finishes or waits on you, on or off."
  [k color]
  (let [on? @(rf/subscribe [:bell? k])]
    [:button.icon.sound-toggle {:type "button" :class (when on? "on") :aria-pressed on? :aria-label "Bell"
                                :data-tip (if on? "Bell on: this chat chimes and pings your phone when it finishes or waits"
                                              "Bell off: this chat stays quiet")
                                :on-click #(ring-bell! k (not on?) color)}
     [:svg {:viewBox "0 0 24 24" :aria-hidden true :fill "none" :stroke "currentColor" :stroke-width 1.8
            :stroke-linecap "round" :stroke-linejoin "round"}
      [:path {:d bell-path}]
      (when-not on? [:path {:d "M4 4l16 16"}])]]))

;; ---- watching the chat lists ----

(defn changes
  "The chats that finished or started waiting between two states of app-db."
  [before after]
  (filter #(#{:waiting :done} (first %)) (rowdiff/row-events before after)))

(defonce ^:private watching (atom false))

(defn install! []
  (when-not @watching
    (reset! watching true)
    ;; The browser lets a page play only after a click or key: wake the context on the first one.
    (doseq [ev ["pointerdown" "keydown"]]
      (js/window.addEventListener ev #(when (and @ctx (= "suspended" (.-state ^js @ctx))) (.resume ^js @ctx)) #js {:capture true}))
    (add-watch rdb/app-db ::alerts
               (fn [_ _ before after]
                 (when-not (identical? (:servers before) (:servers after))
                   (doseq [[kind [s id :as k] row] (changes before after)
                           :when (and (switches/row-bell-on? row)
                                      (or (not= k (:open after)) (not (js/document.hasFocus))))]
                     (play! kind (:color row))
                     (when (:popups @on) (popup! kind s (assoc row :id id)))))))))
