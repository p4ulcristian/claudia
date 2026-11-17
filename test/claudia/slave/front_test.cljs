(ns claudia.slave.front-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.front :as front]))

(def ^:private k "a-hub-key-0123456789012345678901234567890123")

(deftest from-hub-needs-the-exact-key
  (is (front/from-hub? #js {"x-claudia-hub-key" k} k))
  (is (not (front/from-hub? #js {"x-claudia-hub-key" (str k "x")} k)))
  (is (not (front/from-hub? #js {} k)) "no key: not the hub, whatever else it says")
  (is (not (front/from-hub? #js {"x-claudia-hub-key" ""} "")) "a front without a key trusts nobody"))

(deftest runner-headers-never-pass-the-hub-key-on
  (let [out (front/runner-headers #js ["X-Claudia-Hub-Key" k "X-Claudia-Email" "a@example.com" "Accept" "*/*"]
                                  {:target {:profile "paul"}})]
    (is (= [["Accept" "*/*"] ["X-Claudia-Profile" "paul"]] (map vec out)))))
