(ns claudia.web.scene3d.labels-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.labels :as labels]))

(deftest which-labels-show
  (testing "without a story, all of them"
    (is (labels/wanted? {:story? false} "femur")))
  (testing "in a story: the step's focus, else what it brings in, and the part under the pointer"
    (is (labels/wanted? {:story? true :focus #{"femur"}} "femur"))
    (is (not (labels/wanted? {:story? true :focus #{"femur"} :shows #{"tibia"}} "tibia")))
    (is (labels/wanted? {:story? true :focus #{} :shows #{"tibia"}} "tibia"))
    (is (labels/wanted? {:story? true :focus #{"femur"} :hover "tibia"} "tibia"))
    (testing "an overview step (no focus, nothing brought in) shows every label"
      (is (labels/wanted? {:story? true :focus #{} :shows #{}} "tibia")))))

(deftest a-side-for-each-label
  (is (= :left (labels/side-of 100 {:left 200 :right 600})))
  (is (= :right (labels/side-of 450 {:left 200 :right 600}))))

(deftest a-column-never-overlaps
  (testing "labels where they want to be when there is room"
    (is (= [50 100] (labels/stack [50 100] [20 20] 0 400))))
  (testing "two that want the same height are pushed apart, gap between them"
    (is (= [50 76] (labels/stack [50 50] [20 20] 0 400))))
  (testing "pushed past the bottom, the column moves up"
    (is (= [354 380] (labels/stack [385 390] [20 20] 0 390))))
  (testing "not fitting at all, they stack down from the top"
    (is (= [10 36 62] (labels/stack [10 10 10] [20 20 20] 0 40)))))

(deftest columns-stand-beside-the-model
  (is (= {:left-x 182 :right-x 618} (labels/columns {:left 200 :right 600} 1000 {:left 80 :right 90})))
  (testing "a model filling the stage leaves the columns at its edges"
    (is (= {:left-x 98 :right-x 892} (labels/columns {:left 0 :right 1000} 1000 {:left 80 :right 90})))))

(deftest the-layout
  (let [items [{:id "a" :x 300 :y 100 :w 80 :h 20} {:id "b" :x 500 :y 100 :w 60 :h 20}]
        placed (labels/layout items {:left 250 :right 550 :top 50 :bottom 350} 1000 400 [])]
    (is (= {:side :left :cy 100 :top 90 :left 152} (placed "a")))
    (is (= {:side :right :cy 100 :top 90 :left 568} (placed "b")))))

(deftest leader-lines
  (is (= [[300 100] [246 100] [232 100]]
         (labels/leader {:x 300 :y 100} {:side :left :left 152 :cy 100} 80)))
  (is (= [[500 100] [554 100] [568 100]]
         (labels/leader {:x 500 :y 100} {:side :right :left 568 :cy 100} 60))))

(deftest easing-into-place
  (is (= 100 (labels/ease nil 100)))
  (is (= 100 (labels/ease 99.8 100)))
  (is (= 30 (labels/ease 0 100))))

(deftest a-column-keeps-clear-of-legend-and-subtitles
  (let [legend {:left 10 :right 200 :top 10 :bottom 150}
        sub {:left 150 :right 850 :top 300 :bottom 380}]
    (testing "a column under the legend starts below it and ends above the subtitles"
      (is (= {:top 156 :bottom 294} (labels/span 100 190 400 [legend sub]))))
    (testing "a column clear of both reaches the stage's margins"
      (is (= {:top 18 :bottom 382} (labels/span 880 990 400 [legend sub]))))))

(deftest a-full-column-hands-labels-over
  (let [it (fn [id x] {:id id :x x :y 100 :w 50 :h 20})]
    (testing "too many on the left: the ones nearest the middle cross over"
      (is (= {:left [(it "a" 10)] :right [(it "c" 300) (it "b" 20)]}
             (labels/balance {:left [(it "a" 10) (it "b" 20)] :right [(it "c" 300)]} {:left 30 :right 200}))))
    (testing "both full: nothing moves"
      (is (= {:left [(it "a" 10) (it "b" 20)] :right [(it "c" 300)]}
             (labels/balance {:left [(it "a" 10) (it "b" 20)] :right [(it "c" 300)]} {:left 30 :right 20}))))))

(deftest labels-that-do-not-fit-are-left-out
  (let [items (for [i (range 5)] {:id (str i) :x 10 :y 50 :w 40 :h 20})
        placed (labels/layout items {:left 300 :right 700 :top 0 :bottom 100} 1000 100 [{:left 900 :right 1000 :top 0 :bottom 100}])]
    (is (= 2 (count placed)))))
