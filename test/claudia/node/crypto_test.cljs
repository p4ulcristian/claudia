(ns claudia.node.crypto-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.crypto :as crypto]))

(deftest sha256-hex-of-text
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (crypto/sha256-hex "abc"))))

(deftest server-key-differs-per-server-and-secret
  (let [k (crypto/server-key "hub-secret-0123456789012345678901234567890123" "box")]
    (is (re-matches #"^[A-Za-z0-9_-]{43}$" k) "32 bytes, base64url")
    (is (= k (crypto/server-key "hub-secret-0123456789012345678901234567890123" "box")) "the same each time")
    (is (not= k (crypto/server-key "hub-secret-0123456789012345678901234567890123" "mishung")) "per server")
    (is (not= k (crypto/server-key "other-secret-012345678901234567890123456789012" "box")) "per hub")))

(deftest same-secret-compares-whole-texts
  (is (crypto/same-secret? "abc" "abc"))
  (is (not (crypto/same-secret? "abc" "abd")))
  (is (not (crypto/same-secret? "abc" "abcd")))
  (is (not (crypto/same-secret? "abc" nil)))
  (is (not (crypto/same-secret? nil nil))))
