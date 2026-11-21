(ns claudia.web.alerts
  "Sounds and desktop popups when a chat finishes a turn or starts waiting on you (a question).
  Each chat has its own bell (README decision 21, kept with the chat, on unless turned off): with it
  off, that chat makes no sound, no popup and no phone push. Popups are also a per-device switch
  (the browser's permission). The open chat counts only while the window is not in front. Only one
  of a person's devices rings: the page in use (in front, touched in the last minutes), or, when
  none is, every page (claudia.web.presence/sound-here?).

  Sounds are made here with Web Audio (no files): each chat colour has its own note, so you hear
  which chat it was, and each chat its own sound (README decision 90: Bell, Marimba, Chime, Drop,
  Knock; claudia.shared.sounds). A finished turn strikes its note once; a chat waiting on you asks
  with two notes going up. Popups go through the browser's notifications (turning them on asks
  its permission once), one per chat at a time, silent; a click brings the window up and opens the
  chat."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.core :as r]
            [claudia.shared.colours :as colours]
            [claudia.shared.sounds :as sounds]
            [claudia.web.presence :as presence]
            [claudia.web.rowdiff :as rowdiff]
            [claudia.web.views.pure.switches :as switches]))

;; The solid chat colours in hue order (claudia.css --c-*): neighbouring colours get neighbouring
;; notes; a gradient rings its first crayon's note.
(def ^:private colours (conj colours/solid-colours "ash"))

;; A major pentatonic from C4 up: any two notes sound fine together. Index 0 = no colour.
(def ^:private steps [0 2 4 7 9])
(defn- midi [i] (+ 60 (* 12 (quot i (count steps))) (steps (mod i (count steps)))))
(defn- hz [m] (* 440 (js/Math.pow 2 (/ (- m 69) 12))))
(defn- note-of [color]
  (let [c (or (first (colours/crayons color)) color)]
    (midi (if-let [i (first (keep-indexed #(when (= %2 c) %1) colours))] (inc i) 0))))

(defonce ^:private ctx (atom nil))
(defn- audio [] (or @ctx (reset! ctx (js/AudioContext.))))

(defn- envelope!
  "A gain node into the speakers from at: up to gain in attack seconds, then fading over length."
  [^js a at gain attack length]
  (let [out (.createGain a)]
    (.setValueAtTime (.-gain out) 0 at)
    (.linearRampToValueAtTime (.-gain out) gain (+ at attack))
    (.exponentialRampToValueAtTime (.-gain out) 0.0001 (+ at length))
    (.connect out (.-destination a))
    out))

(defn- tone!
  "One oscillator of wave (its type) into out from at for length seconds, at frequency f (or gliding from
  f to f-to in glide seconds), its own level g."
  [^js a out {:keys [wave f f-to glide g at length]}]
  (let [o (.createOscillator a)
        og (.createGain a)]
    (set! (.-type o) wave)
    (.setValueAtTime (.-frequency o) f at)
    (when f-to (.exponentialRampToValueAtTime (.-frequency o) f-to (+ at glide)))
    (set! (.. og -gain -value) g)
    (.connect (.connect o og) out)
    (.start o at)
    (.stop o (+ at length 0.05))))

(defn- struck!
  "A note of partials over f Hz, with an attack and a fade."
  [a f at gain attack length parts]
  (let [out (envelope! a at gain attack length)]
    (doseq [[mult g] (sounds/partials f parts)]
      (tone! a out {:wave "sine" :f (* mult f) :g g :at at :length length}))))

(def ^:private strikes
  "Each sound (claudia.shared.sounds) as one strike: (fn [audio f at gain length]) on the note f."
  {"bell" (fn [a f at gain length] (struck! a f at gain 0.03 length [[1 1] [2 0.12]]))
   "marimba" (fn [a f at gain length] (struck! a f at (* 1.4 gain) 0.004 (* 0.45 length) [[1 1] [4 0.22] [10 0.04]]))
   "chime" (fn [a f at gain length] (struck! a f at (* 0.8 gain) 0.005 (* 2 length) [[1 1] [2.76 0.35] [5.4 0.15] [8.93 0.06]]))
   "drop" (fn [a f at gain length]
            (let [d (sounds/octave-under f 900)]
              (tone! a (envelope! a at (* 1.6 gain) 0.005 (* 0.3 length))
                     {:wave "sine" :f (* 0.7 d) :f-to (* 2.2 d) :glide 0.09 :g 1 :at at :length (* 0.3 length)})))
   "knock" (fn [a f at gain _]
             (let [k (sounds/octave-under f 450)
                   out (envelope! a at (* 2.2 gain) 0.002 0.13)]
               (tone! a out {:wave "triangle" :f (* 2.2 k) :f-to (* 1.5 k) :glide 0.03 :g 1 :at at :length 0.13})
               (tone! a out {:wave "sine" :f (* 3.3 k) :g 0.3 :at at :length 0.05})))})

(defn play!
  "kind: :done or :waiting; color: the chat's colour name; sound: its sound's id (claudia.shared.sounds,
  unknown: the Bell). A finished turn strikes the note once; waiting strikes it, then 4 steps higher."
  [kind color sound]
  (let [^js a (audio)
        f (hz (note-of color))
        up (hz (+ (note-of color) 4))
        strike (strikes (sounds/sound-id sound))
        t (+ (.-currentTime a) 0.02)]
    (when (= "suspended" (.-state a)) (.resume a))
    (if (= kind :done)
      (strike a f t 0.045 0.9)
      (do (strike a f t 0.035 0.5)
          (strike a up (+ t 0.16) 0.035 0.8)))))

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

(defn pick-sound!
  "The notify pick id (a sound id, \"\" Off) for chat k ([server id]; nil: the new chat) in colour
  color: kept with the chat (README decision 90); a sound picked rings once."
  [k id color]
  (rf/dispatch [:chat/switches k (switches/notify-switches id)])
  (when (seq id) (play! :done color id)))

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
                     (.then (presence/sound-here?)
                            (fn [here?]
                              (when here?
                                (play! kind (:color row) (switches/row-sound row))
                                (when (:popups @on) (popup! kind s (assoc row :id id))))))))))))
