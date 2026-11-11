(ns claudia.web.world.budget-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.world.budget :as budget]))

(def on-time (repeat 60 16.7))

(deftest frames-on-time-climb-to-the-cap
  (is (= 1.15 (budget/next-scale 1 on-time 1.5)))
  (is (= 1.5 (budget/next-scale 1.5 on-time 1.5))))

(deftest late-frames-drop-a-step-not-below-the-floor
  (let [late (concat (repeat 5 16.7) (repeat 55 33.3))]
    (is (= 0.85 (budget/next-scale 1 late 1.5)))
    (is (= 0.6 (budget/next-scale 0.6 late 1.5)))))

(deftest a-few-hiccups-change-nothing
  (is (= 1 (budget/next-scale 1 (concat (repeat 50 16.7) (repeat 10 40)) 1.5))))

(deftest too-few-frames-change-nothing
  (is (= 1 (budget/next-scale 1 [50 50] 1.5))))
