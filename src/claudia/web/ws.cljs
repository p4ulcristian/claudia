(ns claudia.web.ws
  "One WebSocket per server (/s/<server>/ws), reconnecting with backoff. The sockets live here, out
  of app-db; what happens on them is re-frame events."
  (:require [re-frame.core :as rf]
            [claudia.shared.protocol :as protocol]))

(defonce ^:private sockets (atom {})) ; server -> {:sock WebSocket :tries n :timer id}

(defn- url [server]
  (str (if (= "https:" js/location.protocol) "wss://" "ws://")
       js/location.host "/s/" (js/encodeURIComponent server) "/ws"))

(declare connect!)

(defn- retry! [server]
  (let [tries (get-in @sockets [server :tries] 0)
        ;; 0.5 s, 1 s, 2 s ... up to 15 s, with a little jitter so servers don't reconnect in step.
        wait (+ (min 15000 (* 500 (js/Math.pow 2 tries))) (rand-int 400))]
    (swap! sockets assoc-in [server :tries] (inc tries))
    (swap! sockets assoc-in [server :timer] (js/setTimeout #(connect! server) wait))))

(defn connect! [server]
  (when-let [t (get-in @sockets [server :timer])] (js/clearTimeout t))
  (let [sock (js/WebSocket. (url server))]
    (swap! sockets assoc-in [server :sock] sock)
    (rf/dispatch [:ws/status server :connecting])
    (set! (.-onopen sock)
          (fn [_]
            (swap! sockets assoc-in [server :tries] 0)
            (rf/dispatch [:ws/opened server])))
    (set! (.-onmessage sock)
          (fn [^js e]
            (when-let [frame (try (protocol/read-json (.-data e)) (catch :default _ nil))]
              (rf/dispatch [:ws/frame server frame]))))
    (set! (.-onclose sock)
          (fn [_]
            (when (identical? sock (get-in @sockets [server :sock]))
              (rf/dispatch [:ws/status server :closed])
              (retry! server))))))

(defn send!
  "Sends a frame on a server's socket; false when the socket isn't open."
  [server frame]
  (let [^js sock (get-in @sockets [server :sock])]
    (if (and sock (= 1 (.-readyState sock)))
      (do (.send sock (protocol/write-json frame)) true)
      false)))

(defn wake!
  "Back in the foreground (a phone unlocks): reconnect now instead of waiting out the backoff."
  []
  (doseq [[server {:keys [^js sock]}] @sockets]
    (when (or (nil? sock) (>= (.-readyState sock) 2))
      (swap! sockets assoc-in [server :tries] 0)
      (connect! server))))

(rf/reg-fx :ws/connect (fn [servers] (doseq [s servers] (connect! s))))

(rf/reg-fx :ws/send (fn [[server frame]] (send! server frame)))

(rf/reg-fx :ws/send-all (fn [pairs] (doseq [[server frame] pairs] (send! server frame))))
