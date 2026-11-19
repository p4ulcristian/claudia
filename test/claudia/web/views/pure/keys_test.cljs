(ns claudia.web.views.pure.keys-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.keys :as pk]))

(def saved (pk/ticked [{:item "i1" :email "p@x"}]))

(deftest boxes-show-the-unsaved-change-first
  (is (pk/on? saved {} "i1" "p@x"))
  (is (not (pk/on? saved {} "i2" "p@x")))
  (is (not (pk/on? saved {["i1" "p@x"] false} "i1" "p@x"))))

(deftest toggling-back-drops-the-change
  (let [c (pk/toggle saved {} "i1" "p@x")]
    (is (= {["i1" "p@x"] false} c))
    (is (= {} (pk/toggle saved c "i1" "p@x"))))
  (is (= {["i2" "a@x"] true} (pk/toggle saved {} "i2" "a@x"))))

(deftest the-batch-is-what-the-broker-takes
  (is (= [{:item "i1" :email "p@x" :on false} {:item "i2" :email "a@x" :on true}]
         (pk/batch {["i2" "a@x"] true ["i1" "p@x"] false}))))

(deftest admins-are-not-ticked
  (is (= [{:email "p@x"}] (pk/others [{:email "me@x"} {:email "p@x"}] ["me@x"]))))
