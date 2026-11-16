(ns claudia.web.presence
  "At the desktop (README, Hub operations: push): a hub page outside the iPhone app tells the hub whether its
  person is working at it, so their phone alerts wait meanwhile. Active = the page shows, has the
  focus, and had mouse, keyboard or touch input within away-after-ms. Said every heartbeat while
  active, and at once when that stops (blur, hidden, idle, the page closing)."
  (:require [claudia.shared.presence :as p]))

(defonce ^:private page (str (random-uuid)))
(defonce ^:private input (atom (js/Date.now)))
(defonce ^:private said (atom nil))
(defonce ^:private at (atom 0))
(defonce ^:private started (atom false))

(defn- active? []
  (and (= "visible" js/document.visibilityState)
       (js/document.hasFocus)
       (< (- (js/Date.now) @input) p/away-after-ms)))

(defn sound-here?
  "Promise of whether this page makes a chat's sound or popup (README decision 21): yes while it is
  in use itself; otherwise only when none of the person's other pages is in use (the hub knows)."
  []
  (if (active?)
    (js/Promise.resolve true)
    (-> (js/fetch (str "/hub/presence?page=" page) #js {:credentials "same-origin"})
        (.then #(.json ^js %))
        (.then #(not (.-elsewhere ^js %)))
        (.catch (fn [_] true)))))

(defn- body [on] (js/JSON.stringify #js {:page page :active on}))

(defn- say! [on]
  (reset! said on)
  (reset! at (js/Date.now))
  (-> (js/fetch "/hub/presence" #js {:method "POST" :credentials "same-origin" :keepalive true
                                     :headers #js {"content-type" "application/json"} :body (body on)})
      (.catch (fn [_] nil))))

(defn say?
  "Whether to tell the hub now: being active changed since it was last said (said), or the page is
  still active and the heartbeat is due (last said at, now)."
  [on said-before said-at now]
  (or (not= on said-before) (and on (>= (- now said-at) p/heartbeat-ms))))

(defn- tick! []
  (let [on (active?)]
    (when (say? on @said @at (js/Date.now))
      (say! on))))

(defn start!
  "Once per page, in a browser and in the iPhone and iPad app alike: a page in use there quiets the
  others (and holds the phone's alerts) just as a desktop's does."
  []
  (when-not @started
    (reset! started true)
    (let [touched (fn [] (reset! input (js/Date.now)) (when-not (true? @said) (tick!)))]
      (doseq [e ["pointerdown" "pointermove" "keydown" "wheel" "touchstart"]]
        (js/window.addEventListener e touched #js {:passive true :capture true})))
    (doseq [e ["focus" "blur"]] (js/window.addEventListener e tick!))
    (js/document.addEventListener "visibilitychange" tick!)
    ;; Closing: said with a beacon, which outlives the page.
    (js/window.addEventListener "pagehide"
                                (fn []
                                  (when @said
                                    (js/navigator.sendBeacon "/hub/presence" (js/Blob. #js [(body false)] #js {:type "application/json"})))
                                  (reset! said false)))
    ;; Catches the input window running out, and the beat.
    (js/setInterval tick! 5000)
    (tick!)))
