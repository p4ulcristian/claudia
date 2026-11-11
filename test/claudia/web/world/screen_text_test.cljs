(ns claudia.web.world.screen-text-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.world.screen-text :as st]))

(deftest status-lines
  (is (= "● working…" (first (st/status {:state :working} 0))))
  (is (= "? waiting on you" (first (st/status {:state :waiting} 0))))
  (is (= "idle · 5m" (first (st/status {:state :idle :at 0} (* 5 60 1000)))))
  (is (= "idle" (first (st/status {:state :idle} 0)))))

(deftest the-body-is-the-question-while-waiting
  (is (= "Ship it?" (st/body {:state :waiting :ask "Ship it?" :preview "Done."})))
  (is (= "Done." (st/body {:state :idle :ask "Ship it?" :preview "Done."})))
  (is (= "No reply yet." (st/body {:state :idle}))))

(deftest wrapping-by-measure
  (let [m count]
    (is (= ["aaa bbb" "ccc"] (st/wrap m 7 3 "aaa bbb ccc")))
    (testing "too many lines: the last is cut with an ellipsis"
      (is (= ["aaa bbb" "ccc dd…"] (st/wrap m 7 2 "aaa bbb ccc ddd eee"))))
    (testing "a word longer than the width is cut"
      (is (= ["abcdef…"] (st/wrap m 7 2 "abcdefghijk"))))
    (is (= [] (st/wrap m 7 2 "  ")))))

(deftest ago-is-short
  (is (= ["now" "5m" "3h" "2d"] (map #(st/ago 0 (* % 1000)) [10 300 10800 172800]))))

(deftest slots-stay-put-and-fill-free-ones
  (is (= {"a" 0 "b" 1} (st/slots {} ["a" "b"] 4)))
  (is (= {"b" 1 "c" 0} (st/slots {"a" 0 "b" 1} ["b" "c"] 4)) "a's slot goes to the new c")
  (is (= {"a" 0 "b" 1} (st/slots {} ["a" "b" "c"] 2)) "past capacity: no screen"))

(deftest far-screens-grow-up-to-a-limit
  (is (= [1 1 1.4] (map st/far-scale [10 45 500])))
  (is (< 1 (st/far-scale 55) 1.4)))
