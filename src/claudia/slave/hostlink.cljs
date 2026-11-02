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

(defn send!
  "One op to the host (ClojureScript data)."
  [op]
  (if @ready
    (.write ^js @sock (str (p/write-json op) "\n"))
    (swap! queue conj op)))

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
                          (on-event ev)
                          (when (and (= "sessions" (:ev ev)) (not @ready))
                            (reset! ready true)
                            (doseq [op (first (reset-vals! queue []))] (send! op))))))))
