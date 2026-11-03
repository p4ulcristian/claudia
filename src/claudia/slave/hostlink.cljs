(ns claudia.slave.hostlink
  "The runner's end of the host socket: sends ops (queued while the host is away), hands host events
  to the chats. Reconnects every second."
  (:require ["node:net" :as net]
            [claudia.shared.protocol :as p]
            [claudia.slave.lines :as lines]))

(defonce ^:private sock (atom nil))
;; Ops wait here until the host has said which sessions it holds.
(defonce ^:private ready (atom false))
(defonce ^:private queue (atom []))
;; Control calls waiting for the host's answer: req -> {:done :fail}.
(defonce ^:private controls (atom {}))
(defonce ^:private next-req (atom 0))

(defn send!
  "One op to the host (ClojureScript data)."
  [op]
  (if @ready
    (.write ^js @sock (str (p/write-json op) "\n"))
    (swap! queue conj op)))

(defn control!
  "Calls method on a live chat's query in the host (PROTOCOL.md: control); a promise of its value
  (ClojureScript data). Rejected when the host has no such session or the call failed."
  [id method & args]
  (js/Promise.
   (fn [done fail]
     (let [req (swap! next-req inc)]
       (swap! controls assoc req {:done done :fail fail})
       (js/setTimeout #(when-let [{late :fail} (get @controls req)]
                         (swap! controls dissoc req)
                         (late (js/Error. "control timed out")))
                      15000)
       (send! {:op "control" :id id :req req :method method :args (vec args)})))))

(defn- on-control [{:keys [req value error]}]
  (when-let [{:keys [done fail]} (get @controls req)]
    (swap! controls dissoc req)
    (if (some? error) (fail (js/Error. error)) (done value))))

(defn connect!
  "on-event gets every host event as data; on-lost is called when the connection drops."
  [socket-path on-event on-lost]
  (let [s (net/connect socket-path)
        lost (atom false)
        lose! (fn []
                (when-not @lost
                  (reset! lost true)
                  (reset! sock nil)
                  (reset! ready false)
                  (on-lost)
                  (js/setTimeout #(connect! socket-path on-event on-lost) 1000)))]
    (reset! sock s)
    (.on s "error" lose!)
    (.on s "close" lose!)
    (lines/on-lines s (fn [line]
                        (let [ev (p/read-json line)]
                          (if (= "control" (:ev ev)) (on-control ev) (on-event ev))
                          (when (and (= "sessions" (:ev ev)) (not @ready))
                            (reset! ready true)
                            (doseq [op (first (reset-vals! queue []))] (send! op))))))))
