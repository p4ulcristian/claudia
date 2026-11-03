(ns claudia.web.views.flow-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.flow :as flow]))

(deftest new-part-is-cut-out
  (is (= [{:s "ab" :color nil :at 0} {:s "cd" :color nil :at 2} {:s "\n" :color nil :at 4} {:s "e" :color nil :at 5}]
         (flow/fresh-pieces (flow/token-lines nil "abcd\ne") 2 4)))
  (is (= [{:s "x" :color nil :at 0}] (flow/fresh-pieces [[{:s "x"}]] -1 -1))))

(deftest ways-and-lanes
  (is (= [{:id "" :frames [{:caption "a"}]}] (flow/ways-of {:frames [{:caption "a"}]})))
  (is (= [{:id "s" :frames []}] (flow/ways-of {:scenarios [{:id "s"}]})))
  (is (= [{:id ""}] (flow/lanes-of {}))))

(deftest cards-and-stations
  (let [lanes [{:id "l1"} {:id "l2"}]
        frames [{:cards [{:at "a" :body "x"}]} {:cards [{:lane "l2" :at "b" :body "y\nz"}]}]
        stations [{:id "s1" :steps [{:id "a"}]} {:id "b"}]]
    (is (= {:at "a" :body "x"} (flow/card-of lanes "l1" (first frames))))
    (is (= {:now nil :card {:at "a" :body "x"}} (flow/lane-card lanes frames 1 "l1")))
    (is (= 2 (flow/lane-lines lanes frames "l2")))
    (is (= "s1" (flow/station-of stations "a")))
    (is (= #{"s1" "b"} (flow/lit-stations stations {:cards [{:at "a"}] :on ["b"]})))
    (is (= "1fr 1fr" (flow/station-columns stations)))
    (is (= {:w 260 :left 0} (flow/card-box {:w 1000 :at {"a" 50}} {:at "a"})))
    (is (= {:w 260 :left 740} (flow/card-box {:w 1000 :at {"a" 990}} {:at "a"})))))
