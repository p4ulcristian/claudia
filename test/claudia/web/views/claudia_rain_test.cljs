(ns claudia.web.views.claudia-rain-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.claudia-rain :as rain]))

(deftest columns-and-falling
  (let [cols (rain/columns 2 100 200 [0.5 0.5 0 0.1 0.9 1])]
    (is (= 2 (count cols)))
    (is (= {:x 50 :y 100 :speed 18 :seed 0} (first cols)))
    (testing "a second later each head fell by its speed"
      (is (= 118 (:y (first (rain/fall cols 1000 200))))))
    (testing "past the bottom it starts again above the top"
      (is (neg? (:y (first (rain/fall [{:x 0 :y 400 :speed 10 :seed 0}] 1000 200))))))))

(deftest a-trail-fades
  (let [t (rain/trail {:x 5 :y 100 :seed 3} 0)]
    (is (= 6 (count t)))
    (is (= 1 (nth (first t) 3)) "the head is brightest")
    (is (> (nth (second t) 3) (nth (last t) 3)) "and the rest fade upward")))
