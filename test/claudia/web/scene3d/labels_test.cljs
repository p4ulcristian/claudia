(ns claudia.web.scene3d.labels-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.labels :as labels]))

(defn- rect [left top] {:left left :right (+ left 50) :top top :bottom (+ top 20)})

(deftest labels-apart-stay-put
  (is (= [{:y 0 :to 0 :side 0} {:y 0 :to 0 :side 0}]
         (labels/targets [(rect 0 0) (rect 100 0)] [nil nil]))))

(deftest a-label-on-another-moves-off-it
  (testing "the lower one moves down below the first (3 px apart)"
    (is (= [{:y 0 :to 0 :side 0} {:y 0 :to 18 :side 1}]
           (labels/targets [(rect 0 0) (rect 10 5)] [nil nil]))))
  (testing "the higher one moves up"
    (is (= {:y 0 :to -18 :side -1}
           (second (labels/targets [(rect 0 10) (rect 10 5)] [nil nil])))))
  (testing "a shifted label is placed from where it would sit unshifted"
    (is (= {:y 23 :to 18 :side 1}
           (second (labels/targets [(rect 0 0) (rect 10 28)] [nil {:y 23 :to 23 :side 1}])))))
  (testing "never more than 90 px"
    (is (= 90 (:to (second (labels/targets [{:left 0 :right 50 :top 0 :bottom 300} (rect 10 160)] [nil nil])))))
    (is (every? #(<= -90 (:to %) 90) (labels/targets (mapv #(rect 0 %) (range 100)) (repeat nil))))))

(deftest clear-of-gives-up-after-ten-moves
  (let [placed (vec (for [i (range 20)] {:left 0 :right 50 :top (* i 21) :bottom (+ (* i 21) 20)}))
        [dy side] (labels/clear-of (rect 0 0) 0 20 0 placed)]
    (is (= 1 side))
    (is (pos? dy))))

(deftest shifts-ease-and-snap
  (is (= {:y 2.5 :to 10} (labels/ease {:y 0 :to 10})))
  (is (= {:y 10 :to 10} (labels/ease {:y 9.7 :to 10})))
  (is (labels/easing? {:y 0 :to 10}))
  (is (not (labels/easing? {:y 9.7 :to 10})))
  (is (= "" (labels/translate 0)))
  (is (= "0 -12.3px" (labels/translate -12.34))))
