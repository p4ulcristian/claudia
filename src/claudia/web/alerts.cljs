(ns claudia.web.alerts
  "Sounds and desktop popups when a chat finishes a turn or starts waiting on you (a question).
  Both off unless turned on (kept per device); the open chat counts only while the window is not in
  front.

  Sounds are made here with Web Audio (no files): each chat colour has its own note, so you hear
  which chat it was. A finished turn rings its note once like a small bell; a chat waiting on you
  asks with two notes going up. Popups go through the browser's notifications (turning them on asks
  its permission once), one per chat at a time, silent; a click brings the window up and opens the
  chat."
  (:require [re-frame.core :as rf]
            [re-frame.db :as rdb]
            [reagent.core :as r]))

;; The chat colours in hue order (claudia.css --c-*): neighbouring colours get neighbouring notes.
(def ^:private colours
  ["burgundy" "red" "orange" "amber" "yellow" "lime" "green" "teal" "cyan" "sky" "blue" "indigo"
   "violet" "purple" "magenta" "pink" "brown" "ash"])

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

(defn- popup! [kind server {:keys [id title]}]
  (when (popups-allowed?)
    (let [n (js/Notification. (if (seq title) title "Chat")
                              #js {:body (if (= kind :done) "Finished" "Waiting for you")
                                   :tag (str server "/" id) :icon "/app-icon/icon-192.png" :silent true})]
      (set! (.-onclick n) (fn []
                            (js/window.focus)
                            (rf/dispatch [:nav/open [server id]])
                            (.close n))))))

;; ---- the toggles ----

(defonce on (r/atom {:sounds (= "on" (js/localStorage.getItem "claudia.sounds"))
                               :popups (and (= "on" (js/localStorage.getItem "claudia.popups")) (popups-allowed?))}))

(defn set-sounds! [v]
  (swap! on assoc :sounds v)
  (js/localStorage.setItem "claudia.sounds" (if v "on" "off"))
  (when v (play! :done (get-in @rdb/app-db [:servers (first (:open @rdb/app-db)) :rows (second (:open @rdb/app-db)) :color]))))

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

(defn toggles
  "The two switches (Settings)."
  []
  (let [{:keys [sounds popups]} @on]
    [:<>
     [switch-row sounds set-sounds! "Sounds" "when a chat finishes or waits on you"]
     [switch-row popups set-popups! "Desktop popups" nil]]))

;; ---- watching the chat lists ----

(defn- rows-of [db] (for [[s {:keys [rows]}] (:servers db) [id row] rows] [[s id] row]))

(defn- changes
  "The chats that finished or started waiting between two states of app-db."
  [old new]
  (let [before (into {} (rows-of old))]
    (for [[k row] (rows-of new)
          :let [was (get before k)]
          :when was
          :let [kind (cond (and (:waiting row) (not (:waiting was))) :waiting
                           (and (:running was) (not (:running row)) (not (:waiting row))) :done)]
          :when kind]
      [kind k row])))

(defonce ^:private watching (atom false))

(defn install! []
  (when-not @watching
    (reset! watching true)
    ;; The browser lets a page play only after a click or key: wake the context on the first one.
    (doseq [ev ["pointerdown" "keydown"]]
      (js/window.addEventListener ev #(when (and @ctx (= "suspended" (.-state ^js @ctx))) (.resume ^js @ctx)) #js {:capture true}))
    (add-watch rdb/app-db ::alerts
               (fn [_ _ old new]
                 (when (and (or (:sounds @on) (:popups @on))
                            (not (identical? (:servers old) (:servers new))))
                   (doseq [[kind [s id :as k] row] (changes old new)
                           :when (or (not= k (:open new)) (not (js/document.hasFocus)))]
                     (when (:sounds @on) (play! kind (:color row)))
                     (when (:popups @on) (popup! kind s (assoc row :id id)))))))))
