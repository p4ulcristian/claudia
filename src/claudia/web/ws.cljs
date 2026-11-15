(ns claudia.web.ws
  "One WebSocket per server (/s/<server>/ws), reconnecting with backoff. The sockets live here, out
  of app-db; what happens on them is re-frame events.

  A phone that loses its network often keeps a dead socket open for minutes, so the page checks
  for itself (PROTOCOL.md, page heartbeat): a socket quiet for idle-ping-ms gets a ping, which the
  hub answers; no frame within pong-wait-ms after it = dead, and a new socket opens at once. A
  user action sent while the socket is closed or quiet waits in the server's queue and goes out
  once the socket is proven alive (a pong or any frame), so nothing typed offline is lost."
  (:require [re-frame.core :as rf]
            [claudia.shared.protocol :as protocol]))

;; server -> {:sock WebSocket :tries n :timer id :heard ms :pinged ms :ready bool :queue [frame]}
(defonce ^:private sockets (atom {}))

(defonce view
  ;; Whose chats the sockets show: nil = the person's own, else a profile name (read only; the hub
  ;; turns ?view= into X-Claudia-View).
  (atom nil))

(defn- url [server]
  (str (if (= "https:" js/location.protocol) "wss://" "ws://")
       js/location.host "/s/" (js/encodeURIComponent server) "/ws"
       (when @view (str "?view=" (js/encodeURIComponent @view)))))

(declare connect!)

(def idle-ping-ms "A socket quiet this long gets a ping." 10000)
(def pong-wait-ms "A ping not answered within this = the socket is dead." 6000)
(def ^:private tick-ms "How often the heartbeat looks at the sockets." 2000)

(defn backoff-ms
  "How long to wait before reconnect number tries+1: 0.5 s, 1 s, 2 s ... up to 15 s, plus jitter
  (ms) so servers don't reconnect in step."
  [tries jitter]
  (+ (min 15000 (* 500 (js/Math.pow 2 tries))) jitter))

(defn pinging?
  "Whether a ping is out and nothing came back since it."
  [{:keys [heard pinged]}]
  (boolean (and pinged (> pinged (or heard 0)))))

(defn beat
  "What the heartbeat does with an open socket at now: :dead (a ping went unanswered), :ping (quiet
  too long) or nil."
  [{:keys [heard pinged] :as entry} now]
  (cond
    (pinging? entry) (when (>= (- now pinged) pong-wait-ms) :dead)
    (>= (- now (or heard 0)) idle-ping-ms) :ping))

(def ^:private asks-again
  "Frames the page sends again by itself after a reconnect (hello re-opens the open chat), or that
  only read: never queued."
  #{"hello" "open" "close" "older" "picture" "agent" "agent_git" "diff" "seen"})

(defn kept?
  "Whether a frame that cannot go out now waits in the queue (a user action) or is dropped."
  [frame]
  (not (contains? asks-again (:type frame))))

(defn- live-sock
  "The server's socket when it is open and has said hello."
  [server]
  (let [{:keys [^js sock ready]} (get @sockets server)]
    (when (and sock ready (= 1 (.-readyState sock))) sock)))

(defn- write! [^js sock frame] (.send sock (protocol/write-json frame)))

(defn- ping! [server]
  (when-let [sock (live-sock server)]
    (when-not (pinging? (get @sockets server))
      (swap! sockets assoc-in [server :pinged] (js/Date.now))
      (write! sock {:type "ping"}))))

(defn- flush! [server]
  (when-let [sock (live-sock server)]
    (let [q (get-in (first (swap-vals! sockets assoc-in [server :queue] [])) [server :queue])]
      (doseq [f q] (write! sock f)))))

(defn- retry! [server]
  (let [tries (get-in @sockets [server :tries] 0)
        t (js/setTimeout #(connect! server) (backoff-ms tries (rand-int 400)))]
    (swap! sockets update server assoc :tries (inc tries) :timer t)))

(defn- heard! [server ^js sock ^js e]
  (when (identical? sock (get-in @sockets [server :sock]))
    (swap! sockets assoc-in [server :heard] (js/Date.now))
    (when-let [frame (try (protocol/read-json (.-data e)) (catch :default _ nil))]
      (when-not (= "pong" (:type frame)) (rf/dispatch [:ws/frame server frame])))
    (when (seq (get-in @sockets [server :queue])) (flush! server))))

(defn- opened! [server ^js sock]
  (when (identical? sock (get-in @sockets [server :sock]))
    (swap! sockets update server assoc :tries 0 :heard (js/Date.now))
    (rf/dispatch [:ws/opened server])))

(defn- closed! [server ^js sock]
  (when (identical? sock (get-in @sockets [server :sock]))
    (swap! sockets assoc-in [server :ready] false)
    (rf/dispatch [:ws/status server :closed])
    (retry! server)))

(defn connect! [server]
  (when-let [t (get-in @sockets [server :timer])] (js/clearTimeout t))
  (let [sock (js/WebSocket. (url server))]
    (swap! sockets update server assoc :sock sock :ready false :pinged nil :timer nil)
    (rf/dispatch [:ws/status server :connecting])
    (set! (.-onopen sock) #(opened! server sock))
    (set! (.-onmessage sock) #(heard! server sock %))
    (set! (.-onclose sock) #(closed! server sock))))

(defn send!
  "Sends a frame on a server's socket now; false when the socket isn't open."
  [server frame]
  (let [^js sock (get-in @sockets [server :sock])]
    (if (and sock (= 1 (.-readyState sock)))
      (do (write! sock frame) true)
      false)))

(defn post!
  "Sends a frame when the server's socket is open and lately heard from; else a user action waits
  in the queue (a quiet socket gets a ping, the queue goes out with its answer) and a read is sent
  if it can be."
  [server frame]
  (let [sock (live-sock server)
        fresh? (and sock (not (pinging? (get @sockets server)))
                    (< (- (js/Date.now) (get-in @sockets [server :heard] 0)) idle-ping-ms))]
    (cond
      fresh? (write! sock frame)
      (kept? frame) (do (swap! sockets update-in [server :queue] (fnil conj []) frame) (ping! server))
      sock (write! sock frame))))

(defn- quiet-close! [^js sock]
  (when sock (set! (.-onclose sock) nil) (.close sock)))

(defn reconnect!
  "Every socket closed, then these servers' opened again (whose chats changed). Their queues stay."
  [servers]
  (let [[old _] (reset-vals! sockets (into {} (map (fn [s] [s {}])) servers))]
    (doseq [[_ {:keys [sock timer]}] old]
      (when timer (js/clearTimeout timer))
      (quiet-close! sock))
    (doseq [s servers]
      (swap! sockets assoc-in [s :queue] (get-in old [s :queue] []))
      (connect! s))))

(defn- redial!
  "A dead socket (its ping went unanswered): dropped, and a new one opened at once."
  [server]
  (quiet-close! (get-in @sockets [server :sock]))
  (swap! sockets assoc-in [server :tries] 0)
  (connect! server))

(defn- tick!
  "The heartbeat, while the page shows (a hidden page's timers are slowed down; wake! covers its
  return)."
  []
  (when (= "visible" js/document.visibilityState)
    (let [now (js/Date.now)]
      (doseq [server (keys @sockets) :when (live-sock server)]
        (case (beat (get @sockets server) now)
          :ping (ping! server)
          :dead (redial! server)
          nil)))))

(def ^:private suspect-after-ms
  "Away longer than this, a socket that still says open is not trusted: after a sleep or a network
  change it is often dead, and the browser takes seconds to notice."
  5000)

(defn suspect?
  "Whether sockets are opened again at once after the page was away for away-ms."
  [away-ms]
  (> away-ms suspect-after-ms))

(defn wake!
  "Back in the foreground after away-ms (a phone unlocks, a laptop wakes): after a long time away,
  every socket is opened again at once (a fresh one is up in a fraction of a second); after a short
  one, only the closed ones, without waiting out their backoff."
  [away-ms]
  (if (suspect? away-ms)
    (reconnect! (keys @sockets))
    (doseq [[server {:keys [^js sock]}] @sockets]
      (when (or (nil? sock) (>= (.-readyState sock) 2))
        (swap! sockets assoc-in [server :tries] 0)
        (connect! server)))))

(defonce ^:private hidden-at (atom nil))

(defn- network! [online?]
  (rf/dispatch [:net/online online?])
  (reconnect! (keys @sockets)))

(defn install!
  "Watches the page leave and come back and the network go and come back (to open the sockets
  again fast and show it), and starts the heartbeat."
  [on-visible]
  (rf/dispatch [:net/online (.-onLine js/navigator)])
  (js/document.addEventListener
   "visibilitychange"
   #(if (= "visible" js/document.visibilityState)
      (do (wake! (- (js/Date.now) (or @hidden-at (js/Date.now)))) (on-visible))
      (reset! hidden-at (js/Date.now))))
  (js/window.addEventListener "online" #(network! true))
  (js/window.addEventListener "offline" #(network! false))
  (js/window.addEventListener "pageshow" #(when (.-persisted ^js %) (reconnect! (keys @sockets))))
  (js/setInterval tick! tick-ms))

(rf/reg-fx :ws/reconnect (fn [{v :view :keys [servers]}] (reset! view v) (reconnect! servers)))

(rf/reg-fx :ws/connect (fn [servers] (doseq [s servers] (connect! s))))

(rf/reg-fx :ws/send (fn [[server frame]] (post! server frame)))

(rf/reg-fx :ws/send-all (fn [pairs] (doseq [[server frame] pairs] (post! server frame))))

(defn hello!
  "A socket that just opened: its frames (plain frames, claudia.web.events/hello-frames: hello and
  the open chat) go first, then it is ready and its queue goes."
  [[server frames]]
  (doseq [f frames] (send! server f))
  (swap! sockets assoc-in [server :ready] true)
  (flush! server))

(rf/reg-fx :ws/hello hello!)
