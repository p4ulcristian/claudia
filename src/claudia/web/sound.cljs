(ns claudia.web.sound
  "One sound at a time on the page (2026-11-21, the user's call: playing a message stops the one
  before): a chat's reply read aloud, a 3D story's narrator. A player
  claims the sound as it starts and whatever another player was playing stops.")

(defonce ^:private holder (atom nil))

(defn claim!
  "Player id starts a sound, stop! silences it: the sound another player holds stops."
  [id stop!]
  (let [[old] (swap-vals! holder (constantly {:id id :stop stop!}))]
    (when (and old (not= id (:id old))) ((:stop old)))))

(defn release!
  "Player id's sound is over: nothing to stop for it any more."
  [id]
  (swap! holder #(when-not (= id (:id %)) %)))

(def ^:private stopped-states
  "An audio context's states in which nothing sounds: suspended (made before a touch, or paused) and
  WebKit's own interrupted (iOS took the sound away: a call, Siri, or the app's own recording; README
  decision 121). Both go on only through resume."
  #{"suspended" "interrupted"})

(defn stopped-state?
  "Whether an audio context in this state needs resume before anything sounds."
  [state]
  (contains? stopped-states state))

(defn resumed!
  "Context c goes on when it was stopped (README decision 121): resume is asked and a refusal
  ignored (outside a touch iOS may refuse; the next touch asks again)."
  [^js c]
  (when (stopped-state? (.-state c)) (.catch (.resume c) (fn [_] nil))))
