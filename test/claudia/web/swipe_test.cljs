(ns claudia.web.swipe-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.swipe :as swipe]))

(deftest takes-sideways-for-scrollers-and-touch-action
  (is (true? (swipe/takes-sideways? {:overflow-x "auto" :scroll-width 300 :client-width 200})))
  (is (false? (swipe/takes-sideways? {:overflow-x "auto" :scroll-width 201 :client-width 200})))
  (is (false? (swipe/takes-sideways? {:overflow-x "hidden" :scroll-width 300 :client-width 200})))
  (is (true? (swipe/takes-sideways? {:touch-action "none"})))
  (is (true? (swipe/takes-sideways? {:touch-action "pan-y"})))
  (is (false? (swipe/takes-sideways? {:touch-action "pan-x pan-y"})))
  (is (false? (swipe/takes-sideways? {:touch-action "manipulation"}))))

(deftest pick-move-by-direction
  (let [moves [{:dir 1 :id :back} {:dir -1 :id :over}]]
    (is (= :back (:id (swipe/pick-move moves 20 5 true))))
    (is (= :over (:id (swipe/pick-move moves -20 5 true))))
    (testing "mostly vertical, or not takeable: none"
      (is (nil? (swipe/pick-move moves 10 10 true)))
      (is (nil? (swipe/pick-move moves 20 5 false))))
    (is (nil? (swipe/pick-move [{:dir 1}] -20 0 true)))))

(deftest progress-is-clamped
  (is (= 0.5 (swipe/progress 100 150 1 100)))
  (is (= 0.5 (swipe/progress 150 100 -1 100)))
  (is (= 0 (swipe/progress 100 50 1 100)))
  (is (= 1 (swipe/progress 0 500 1 100))))

(deftest recent-samples-keep-the-last-100-ms
  (is (= [{:x 2 :t 150} {:x 3 :t 200}] (swipe/recent-samples [{:x 1 :t 0} {:x 2 :t 150}] 3 200)))
  (testing "at least two"
    (is (= [{:x 2 :t 50} {:x 3 :t 400}] (swipe/recent-samples [{:x 1 :t 0} {:x 2 :t 50}] 3 400)))
    (is (= [{:x 3 :t 400}] (swipe/recent-samples [] 3 400)))))

(deftest velocity-and-completion
  (is (= 1 (swipe/velocity [{:x 0 :t 0} {:x 100 :t 100}] 1)))
  (is (= -1 (swipe/velocity [{:x 0 :t 0} {:x 100 :t 100}] -1)))
  (is (= 0 (swipe/velocity [{:x 0 :t 5}] 1)))
  (is (true? (swipe/completes? 0.5 0.1)))
  (is (false? (swipe/completes? -0.5 0.9)))
  (is (true? (swipe/completes? 0 0.5)))
  (is (false? (swipe/completes? 0 0.3))))

(deftest animation-timing
  (is (= 120 (swipe/animation-ms 0.9 1 100 1)))
  (is (= 320 (swipe/animation-ms 0 1 1000 0.1)))
  (is (= 200 (swipe/animation-ms 0 1 180 0.9)))
  (is (= 0 (swipe/ease-out 0)))
  (is (= 1 (swipe/ease-out 1))))

(deftest gesture-kind-per-screen
  (is (= :settings (swipe/gesture-kind "list" true false)))
  (is (= :list (swipe/gesture-kind "list" false false)))
  (is (= :agent (swipe/gesture-kind "chat" false true)))
  (is (= :chat (swipe/gesture-kind "chat" true false)))
  (is (= :picture (swipe/gesture-kind "picture" false false)))
  (is (nil? (swipe/gesture-kind nil false false))))
