(ns claudia.web.views.pure.system-chart-test
  "System's 7-day chart (README decision 95), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.system-chart :as c]))

(def day c/day-ms)
;; 2026-11-16 00:00 UTC was a Monday.
(def monday (js/Date.UTC 2026 10 16))

(deftest the-days-fall-where-the-readers-do
  (is (= [{:at monday :name "Mon"} {:at (+ monday day) :name "Tue"}]
         (c/days (- monday 1000) (+ monday day 1000) 0)))
  (is (= [{:at (- monday 3600000) :name "Mon"}] (c/days (- monday 7200000) (+ monday 1000) -60))
      "an hour ahead of UTC: the day starts an hour earlier"))

(deftest the-days-as-spans
  (is (= [{:at (- monday 1000) :end monday :name "Sun"} {:at monday :end (+ monday day) :name "Mon"}
          {:at (+ monday day) :end (+ monday day 1000) :name "Tue"}]
         (c/day-spans (- monday 1000) (+ monday day 1000) 0)))
  (is (= [{:at monday :end (+ monday 5) :name "Mon"}] (c/day-spans monday (+ monday 5) 0))
      "from a midnight: no empty first span"))

(deftest where-things-sit
  (is (= 50 (c/x-of 5 0 10 100)))
  (is (= ["red" "red" "amber" "amber"] (map c/event-tone ["oom" "crash" "failed" "reboot"])))
  (is (= 25 (c/row-cores {:cores 4 :coresTotal 16})))
  (is (nil? (c/row-cores {:cores 4}))))

(deftest lines-and-marks
  (let [to (* 7 day)
        rows [{:at 0 :cores 4 :coresTotal 16 :mem 1 :memTotal 2} {:at (* 3 day) :cores 8 :coresTotal 16 :mem 1 :memTotal 2}]]
    (is (= ["cores" "memory"] (map :id (c/lines rows #{"cores" "memory"} to 300 100))))
    (is (= ["green"] (map :tone (c/lines rows #{"cores"} to 300 100)))))
  (is (= 24 (c/slot-count 10)))
  (is (= 100 (c/slot-count 300)))
  (let [ms (c/marks [{:at (* 6 day) :kind "oom"} {:at (* 1 day) :kind "failed"} {:at -5 :kind "crash"}] (* 7 day) 700)]
    (is (= [[100 "amber"] [600 "red"]] (map (juxt :x :tone) ms)) "oldest first, the week only")))
