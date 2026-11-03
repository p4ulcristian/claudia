(ns claudia.node.http-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.http :as http]))

(deftest parse-object-gives-a-map-or-nothing
  (is (= {:text "hi" :n 1} (http/parse-object "{\"text\":\"hi\",\"n\":1}")))
  (is (= {} (http/parse-object "")))
  (is (= {} (http/parse-object "{broken")))
  (is (= {} (http/parse-object "[1,2]")))
  (is (= {} (http/parse-object "null"))))

(deftest url-host-brackets-ipv6
  (is (= "10.0.0.2" (http/url-host "10.0.0.2")))
  (is (= "front.test" (http/url-host "front.test")))
  (is (= "[fd00::2]" (http/url-host "fd00::2"))))

(deftest decode-or-nothing
  (is (= "a b" (http/decode "a%20b")))
  (is (nil? (http/decode "%E0"))))
