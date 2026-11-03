(ns claudia.web.scene3d.story-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.story :as story]))

(deftest steps-are-made-whole
  (let [[s] (story/normalize [{:say "  hi " :show "a" :camera {:dist "3"} :move 50} "junk"])]
    (is (= "hi" (:say s)))
    (is (= ["a"] (:show s)))
    (is (= 3 (:dist (:camera s))))
    (is (= 20 (:move s)))
    (is (= 0 (:hold s))))
  (is (= [] (story/normalize nil))))

(deftest patterns
  (is (story/matches? "blade*" "blade2"))
  (is (not (story/matches? "blade" "blade2")))
  (is (story/matches-any? ["x" "b*"] "bolt")))

(deftest what-is-on-at-each-step
  (let [steps (story/normalize [{:show ["a"]} {:show ["b*"] :hide ["a"]}])
        ids ["a" "b1" "floor"]]
    (testing "parts the story never shows are on from the start"
      (is (= #{"floor"} (story/visible-at steps -1 ids))))
    (is (= #{"floor" "a"} (story/visible-at steps 0 ids)))
    (is (= #{"floor" "b1"} (story/visible-at steps 1 ids)))))

(deftest poses-and-timing
  (let [steps (story/normalize [{:pose {:anna "wave"}} {} {:pose {:bob "sit"}}])]
    (is (= "wave" (story/pose-at steps 1 "anna")))
    (is (nil? (story/pose-at steps 2 "carl"))))
  (is (= 1.6 (story/estimate-seconds {:say "" :hold 0 :move 1.4})))
  (is (= 3 (story/estimate-seconds {:say "" :hold 3 :move 1.4 :pose {}})))
  (is (= (+ 0.6 (/ 2 2.6)) (story/estimate-seconds {:say "one two" :hold 0 :move 1.4}))))
