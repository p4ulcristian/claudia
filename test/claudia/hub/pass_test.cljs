(ns claudia.hub.pass-test
  (:require [cljs.test :refer [async deftest is]]
            [claudia.hub.pass :as pass]
            ["http" :as http]
            ["net" :as net]
            ["ws" :refer [WebSocket WebSocketServer]]))

(defn- handshake-request [extensions]
  (str "GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
       "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
       "Sec-WebSocket-Extensions: " extensions "\r\n\r\n"))

(defn- first-line-of-answer
  "Sends a handshake offering `extensions` to `port` and calls `done` with the answer's status line."
  [port extensions done]
  (let [sock (net/connect port "127.0.0.1")]
    (.on sock "connect" #(.write sock (handshake-request extensions)))
    (.once sock "data" (fn [d] (.destroy sock) (done (first (.split (str d) "\r\n")))))))

(deftest page-deflate-accepts-every-browser
  (async done
    (let [wss (WebSocketServer. #js {:noServer true :perMessageDeflate pass/page-deflate})
          server (http/createServer)]
      (.on server "upgrade" (fn [req sock head] (.handleUpgrade wss req sock head #(.close %))))
      (.listen server 0 "127.0.0.1"
               (fn []
                 (let [port (.-port (.address server))]
                   (first-line-of-answer port "permessage-deflate"
                     (fn [safari]
                       (is (= "HTTP/1.1 101 Switching Protocols" safari) "Safari and Firefox")
                       (first-line-of-answer port "permessage-deflate; client_max_window_bits"
                         (fn [chrome]
                           (is (= "HTTP/1.1 101 Switching Protocols" chrome) "Chrome")
                           (.close server)
                           (done)))))))))))

(deftest request-headers-pass-only-a-few
  (is (= {"x-claudia-email" "ann@example.com" "x-claudia-view" "paul" "content-type" "text/plain"}
         (pass/request-headers #js {"content-type" "text/plain" "cookie" "login=1"} {:email "ann@example.com" :view "paul"})))
  (is (= {"x-claudia-email" "ann@example.com"} (pass/request-headers #js {} {:email "ann@example.com"}))))

(deftest hub-headers-name-whose-chats-the-caller-may-write
  (is (= {"x-claudia-email" "ann@example.com" "x-claudia-writes" "bo@example.com,cy@example.com"}
         (pass/hub-headers {:email "ann@example.com" :writes ["bo@example.com" "cy@example.com"]})))
  (is (= {"x-claudia-email" "ann@example.com"} (pass/hub-headers {:email "ann@example.com" :writes []}))))

(deftest response-headers-drop-hop-by-hop-and-cookies
  (is (= {"content-type" "text/html" "etag" "x"}
         (js->clj (pass/response-headers #js {"content-type" "text/html" "connection" "keep-alive"
                                              "set-cookie" "a=1" "etag" "x"})))))

(deftest closing-code-passes-what-may-be-sent
  (is (= 1000 (pass/closing-code 1000 true)))
  (is (= 4001 (pass/closing-code 4001 false)))
  (is (= 1014 (pass/closing-code 1006 true)))
  (is (= 1001 (pass/closing-code 1006 false)))
  (is (nil? (pass/closing-code 1005 true))))

(deftest front-url-brackets-ipv6
  (is (= "ws://10.0.0.2:4540/ws" (pass/front-url {:host "10.0.0.2" :port 4540} "/ws")))
  (is (= "ws://[fd00::2]:4540/ws?view=a" (pass/front-url {:host "fd00::2" :port 4540} "/ws?view=a"))))

(deftest upgrade-refusal-is-plain-http
  (is (= (str "HTTP/1.1 404 Not Found\r\ncontent-type: text/plain\r\ncontent-length: 2\r\n"
              "cache-control: no-store\r\nconnection: close\r\n\r\nno")
         (pass/upgrade-refusal 404 "no" "text/plain"))))

(deftest only-the-exact-ping-is-answered-by-the-hub
  (is (pass/ping-frame? (js/Buffer.from "{\"type\":\"ping\"}") false))
  (is (not (pass/ping-frame? (js/Buffer.from "{\"type\":\"ping\"}") true)) "binary")
  (is (not (pass/ping-frame? (js/Buffer.from "{\"type\":\"send\",\"text\":\"ping\"}") false))))

(deftest the-hub-answers-the-page-ping-and-passes-the-rest
  (async done
    (let [front-wss (WebSocketServer. #js {:port 0 :host "127.0.0.1"})
          got (atom [])
          hub (http/createServer)]
      (.on front-wss "connection" (fn [s] (.on s "message" #(swap! got conj (str %)))))
      (.on front-wss "listening"
           (fn []
             (.on hub "upgrade"
                  (fn [req sock head]
                    (pass/ws! req sock head {:host "127.0.0.1" :port (.-port (.address front-wss))} "/ws" {:email "a@example.com"})))
             (.listen hub 0 "127.0.0.1"
                      (fn []
                        (let [page (WebSocket. (str "ws://127.0.0.1:" (.-port (.address hub)) "/"))]
                          (.on page "open" #(.send page "{\"type\":\"ping\"}"))
                          (.on page "message"
                               (fn [m]
                                 (is (= "{\"type\":\"pong\"}" (str m)))
                                 (.send page "{\"type\":\"hello\"}")
                                 (js/setTimeout
                                  (fn []
                                    (is (= ["{\"type\":\"hello\"}"] @got) "the ping never reached the front")
                                    (.terminate page) (.close hub) (.close front-wss) (done))
                                  100)))))))))))

(deftest a-restarting-front-is-tried-again-only-when-safe
  (is (true? (pass/retry? "GET" "ECONNREFUSED" 1000 2000)))
  (is (false? (pass/retry? "POST" "ECONNREFUSED" 1000 2000)) "a body already went out")
  (is (false? (pass/retry? "GET" "ETIMEDOUT" 1000 2000)) "an asleep front times out: no waiting")
  (is (false? (pass/retry? "GET" "ECONNREFUSED" 2000 2000)) "time is up"))
