(ns claudia.node.sealbox-test
  (:require [cljs.test :refer [deftest is async]]
            [claudia.node.sealbox :as sealbox]
            [claudia.slave.secret :as secret]))

(deftest a-sealed-value-opens-only-with-its-request-key
  (async done
    (.then sealbox/ready
           (fn []
             (let [kp (secret/keypair)
                   other (secret/keypair)
                   sealed (sealbox/seal "tok-123" (:pubkey kp))]
               (is (= "tok-123" (secret/open sealed kp)))
               (is (thrown? js/Error (secret/open sealed other)))
               (done))))))
