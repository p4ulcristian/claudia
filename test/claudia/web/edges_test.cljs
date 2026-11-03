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
