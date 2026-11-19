(ns claudia.node.http-test
  (:require [cljs.test :refer [deftest is testing]]
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

(deftest which-bytes-a-range-asks-for
  (is (nil? (http/byte-range nil 100)) "no header: the whole file")
  (is (= {:start 0 :end 1} (http/byte-range "bytes=0-1" 100)) "Safari's first probe")
  (is (= {:start 10 :end 99} (http/byte-range "bytes=10-" 100)))
  (is (= {:start 10 :end 99} (http/byte-range "bytes=10-500" 100)) "an end past the file is cut")
  (is (= {:start 90 :end 99} (http/byte-range "bytes=-10" 100)) "the last 10")
  (is (= {:start 0 :end 99} (http/byte-range "bytes=-500" 100)))
  (testing "ranges that can't be served"
    (is (= :bad (http/byte-range "bytes=100-" 100)))
    (is (= :bad (http/byte-range "bytes=5-2" 100)))
    (is (= :bad (http/byte-range "bytes=-0" 100))))
  (testing "headers this doesn't read: the whole file"
    (is (nil? (http/byte-range "bytes=0-1,5-9" 100)))
    (is (nil? (http/byte-range "items=0-1" 100)))
    (is (nil? (http/byte-range "bytes=-" 100)))))
