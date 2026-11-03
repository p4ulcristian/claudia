(ns claudia.node.crypto-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.crypto :as crypto]))

(deftest sha256-hex-of-text
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (crypto/sha256-hex "abc"))))
