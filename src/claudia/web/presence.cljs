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

(defn- body [on] (js/JSON.stringify #js {:page page :active on}))

(defn- say! [on]
  (reset! said on)
  (reset! at (js/Date.now))
  (-> (js/fetch "/hub/presence" #js {:method "POST" :credentials "same-origin" :keepalive true
                                     :headers #js {"content-type" "application/json"} :body (body on)})
      (.catch (fn [_] nil))))

(defn- tick! []
  (let [on (active?)]
    (when (or (not= on @said) (and on (>= (- (js/Date.now) @at) p/heartbeat-ms)))
      (say! on))))

(defn start!
  "Once, in a browser (not inside the iPhone app, whose own pages are the phone)."
  []
  (when-not (or @started (.-claudiaNative js/window))
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
