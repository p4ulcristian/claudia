(ns claudia.web.hello-test
  "A socket's first frames, from the event to the bytes on the wire: what the page writes when a
  socket opens is what the runner answers (PROTOCOL.md, page -> runner), never `Unknown frame`."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [claudia.shared.protocol :as p]
            [claudia.web.events :as ev]
            [claudia.web.ws :as ws]))

(def ^:private written (atom []))

(defn- fake-socket
  "A WebSocket class that is open at once and keeps what is written on it."
  []
  (fn [_url]
    (this-as ^js this
      (set! (.-readyState this) 1)
      (set! (.-send this) #(swap! written conj %))
      (set! (.-close this) (fn [] (set! (.-readyState this) 3)))
      this)))

(use-fixtures :each
  {:before (fn []
             (reset! written [])
             (set! js/globalThis.location #js {:protocol "http:" :host "hub.test"})
             (set! js/globalThis.WebSocket (fake-socket)))
   :after (fn [] (ws/reconnect! []))})

(defn- open-socket!
  "Opens server's (fake) socket and runs what its opening does on the wire, from the real event's
  effects; the frames written, read back."
  [db server]
  (ws/connect! server)
  (ws/hello! (:ws/hello (ev/opened-fx db server 0)))
  (mapv p/read-json @written))

(defn- runner-knows? [frame]
  (and (map? frame) (contains? p/page->runner (:type frame))))

(deftest a-new-socket-writes-hello-as-a-plain-frame
  (is (= [{:type "hello"}] (open-socket! {} "s")))
  (is (= ["{\"type\":\"hello\"}"] @written) "byte for byte what the runner reads"))

(deftest a-reconnect-opens-the-open-chat-again
  (is (= [{:type "hello"} {:type "open" :chat "c"}] (open-socket! {:open ["s" "c"]} "s"))))

(deftest every-first-frame-is-one-the-runner-knows
  (doseq [db [{} {:open ["s" "c"]} {:open ["other" "c"]} {:open ["s" nil]}]]
    (is (every? runner-knows? (open-socket! db "s")) (pr-str db))
    (reset! written [])))

(deftest a-user-action-waiting-goes-out-after-hello
  (ws/connect! "s")
  (ws/post! "s" {:type "send" :chat "c" :text "hi"})
  (is (empty? @written) "the socket has not said hello: the message waits")
  (ws/hello! (:ws/hello (ev/opened-fx {:open ["s" "c"]} "s" 0)))
  (is (= ["hello" "open" "send"] (mapv (comp :type p/read-json) @written)))
  (is (every? runner-knows? (mapv p/read-json @written))))
