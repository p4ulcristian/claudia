(ns claudia.web.edges-test
  "The small decisions of the page's edges: socket backoff, push registration, presence."
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.presence :as p]
            [claudia.web.native :as native]
            [claudia.web.presence :as presence]
            [claudia.web.ws :as ws]))

(deftest backoff-doubles-up-to-15-s
  (is (= 500 (ws/backoff-ms 0 0)))
  (is (= 2100 (ws/backoff-ms 2 100)))
  (is (= 15000 (ws/backoff-ms 10 0))))

(deftest heartbeat-pings-a-quiet-socket-and-drops-a-silent-one
  (is (nil? (ws/beat {:heard 1000} 5000)) "heard lately")
  (is (= :ping (ws/beat {:heard 1000} (+ 1000 ws/idle-ping-ms))) "quiet")
  (is (= :ping (ws/beat {:heard 9000 :pinged 2000} 20000)) "the last ping was answered")
  (is (nil? (ws/beat {:heard 1000 :pinged 20000} 22000)) "a ping out, still within the wait")
  (is (= :dead (ws/beat {:heard 1000 :pinged 20000} (+ 20000 ws/pong-wait-ms))) "a ping unanswered")
  (is (false? (ws/pinging? {:heard 5 :pinged nil})))
  (is (true? (ws/pinging? {:pinged 5}))))

(deftest only-user-actions-wait-for-the-socket
  (is (ws/kept? {:type "send" :chat "c" :text "hi"}))
  (is (ws/kept? {:type "new" :id "x"}))
  (is (ws/kept? {:type "answer"}))
  (is (not (ws/kept? {:type "open" :chat "c"})))
  (is (not (ws/kept? {:type "seen"})))
  (is (not (ws/kept? {:type "hello"}))))

(deftest push-registration-retries-only-failures
  (is (true? (native/retry? nil 0)))
  (is (true? (native/retry? 503 0)))
  (is (false? (native/retry? 403 0)))
  (is (false? (native/retry? 200 0)))
  (is (false? (native/retry? 500 9))))

(deftest presence-says-changes-and-the-heartbeat
  (is (true? (presence/say? true false 0 0)))
  (is (true? (presence/say? false true 0 0)))
  (is (false? (presence/say? true true 0 (dec p/heartbeat-ms))))
  (is (true? (presence/say? true true 0 p/heartbeat-ms)))
  (is (false? (presence/say? false false 0 p/heartbeat-ms))))

(deftest a-socket-is-not-trusted-after-a-long-time-away
  (is (not (ws/suspect? 1000)))
  (is (ws/suspect? 60000)))
