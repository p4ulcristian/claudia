(ns claudia.web.views.pure.system-tiles-test
  "System's tiles (README decision 96), on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.system-tiles :as t]))

(def cores (concat [{:busy 100 :user 98 :sys 2} {:busy 70}] (repeat 14 {:busy 14.3})))

(deftest cpu-is-busy-cores-of-all
  (let [b (t/cpu-busy {:cores cores})]
    (is (= 16 (:total b)))
    (is (= "3.7 of 16 cores busy (23%)" (apply str (t/cpu-parts b))) "the user: full CPU or one core?"))
  (is (= "1.0 of 1 core busy (100%)" (apply str (t/cpu-parts (t/cpu-busy {:cores [{:busy 100}]})))))
  (is (= {:busy 0 :total 0 :percent 0} (t/cpu-busy {:cores []}))))

(deftest pinned-cores
  (is (= [[0 100]] (t/pinned cores)))
  (is (= "core 0 pinned at 100%" (t/pinned-text [[0 100]])))
  (is (= "cores 2 and 5 pinned" (t/pinned-text [[2 99] [5 100]])))
  (is (= "cores 1, 2 and 5 pinned" (t/pinned-text [[1 99] [2 99] [5 100]])))
  (is (= "4 cores pinned" (t/pinned-text [[1 99] [2 99] [3 99] [5 100]])))
  (is (nil? (t/pinned-text []))))

(deftest the-busiest-process
  (is (= "a" (:name (t/busiest [{:name "a" :cores 1.2} {:name "b" :cores 0.1}]))))
  (is (nil? (t/busiest [{:name "idle" :cores 0.01}])) "a trace is not worth naming")
  (is (nil? (t/busiest []))))

(deftest the-core-key-names-steal-only-when-there-is-some
  (is (= ["user" "sys" "iowait"] (map first (t/core-key [{:steal 0}]))))
  (is (= "steal" (first (last (t/core-key [{:steal 3}]))))))

(deftest waiting
  (is (nil? (t/waiting-rows nil)) "macOS: no pressure")
  (is (= [{:label "for CPU" :value 4 :tone "green"} {:label "for memory" :value 14 :tone "amber"}
          {:label "for disk" :value 41 :tone "red"}]
         (t/waiting-rows {:cpu {:some 4 :full nil} :memory {:some 14 :full 2} :io {:some 41 :full 9}})))
  (is (= 41 (t/waiting-most {:cpu {:some 4} :memory {:some 14} :io {:some 41}})))
  (is (nil? (t/waiting-most nil))))

(deftest memory-disk-cards
  (is (= {:percent 50 :used 4 :total 8} (t/memory-tile {:used 4 :total 8})))
  (is (= "/home" (:mount (t/fullest [{:mount "/" :used 10 :avail 90} {:mount "/home" :used 80 :avail 20}]))))
  (is (= 80 (:percent (t/fullest [{:mount "/" :used 10 :avail 90} {:mount "/home" :used 80 :avail 20}]))))
  (is (nil? (t/fullest [])))
  (is (= {:used 6 :total 24 :percent 25 :name "RTX"} (t/gpu-tile [{:name "RTX" :memUsed 6 :memTotal 24}])))
  (is (= "2 cards" (:name (t/gpu-tile [{:memUsed 1 :memTotal 2} {:memUsed 1 :memTotal 2}]))))
  (is (nil? (t/gpu-tile []))))

(deftest lines-of-the-last-hours
  (testing "rows in time slots, each slot's highest"
    (is (= [[0 5] [2 1]] (t/slots [{:at 0 :v 1} {:at 5 :v 5} {:at 25 :v 1} {:at 99 :v nil}] :v 0 30 3)))
    (is (= [] (t/slots [{:at 50 :v 1}] :v 0 30 3)) "outside the window"))
  (testing "a path; an empty run breaks it"
    (is (= "M5 10 L15 0" (t/line-path [[0 0] [1 10]] 10 10 100 10)))
    (is (= "M5 10 M25 5" (t/line-path [[0 0] [2 5]] 10 10 100 10)))
    (is (= "M5 0" (t/line-path [[0 99]] 10 10 100 10)) "over the top stays at the top"))
  (is (= 2.6 (t/level-y 90 100 26)))
  (is (nil? (t/spark [{:at 100 :cores 1}] :cores 4 100)) "one slot is no line")
  (is (string? (t/spark [{:at 0 :cores 1} {:at 86400000 :cores 2}] :cores 4 86400000)))
  (is (= 50 (t/row-memory {:mem 4 :memTotal 8})))
  (is (nil? (t/row-memory {:mem 4})))
  (is (= 7 (t/row-waiting {:pressure {:cpu 1 :memory 7 :io 2}})))
  (is (= 20 (t/waiting-top [{:pressure {:cpu 1 :memory 7 :io 2}}])))
  (is (= 33 (t/waiting-top [{:pressure {:cpu 33 :memory 7 :io 2}}]))))
