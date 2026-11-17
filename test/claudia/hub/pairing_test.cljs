(ns claudia.hub.pairing-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.pairing :as pairing]))

(def ^:private secret "abcdefghijABCDEFGHIJ0123456789-_abcdefghijk")

(deftest parse-key-takes-host-and-secret
  (is (= {:host "box" :secret secret} (pairing/parse-key (str " claudia-pair:box:" secret " "))))
  (is (nil? (pairing/parse-key (str "claudia-pair:box:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=:" secret)))
      "the old WireGuard key is refused")
  (is (nil? (pairing/parse-key "claudia-pair:box:short")))
  (is (nil? (pairing/parse-key (str "other:box:" secret)))))

(deftest pair-problem-needs-an-address-for-a-new-server
  (is (nil? (pairing/pair-problem "box" "100.101.2.4:4540" nil)))
  (is (nil? (pairing/pair-problem "box" "" {:name "box"})) "a known server keeps its address")
  (is (re-find #"^Address: where" (pairing/pair-problem "box" "" nil)))
  (is (re-find #"^Address: host:port" (pairing/pair-problem "box" "nope" nil)))
  (is (re-find #"^A server name" (pairing/pair-problem "Box" "100.101.2.4:4540" nil))))

(deftest suggest-name-from-host
  (is (= "my-box" (pairing/suggest-name "My.Box")))
  (is (= "server" (pairing/suggest-name "123"))))
