(ns claudia.web.scene3d.maths-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.maths :as m]))

(defn- near? [a b] (< (js/Math.abs (- a b)) 1e-9))
(defn- near3? [a b] (every? true? (map near? a b)))

(deftest points-add-subtract-scale-and-measure
  (is (= [5 7 9] (m/v+ [1 2 3] [4 5 6])))
  (is (= [-3 -3 -3] (m/v- [1 2 3] [4 5 6])))
  (is (= [2 4 6] (m/v* [1 2 3] 2)))
  (is (= 5 (m/vlen [3 4 0])))
  (is (= 5 (m/vdist [0 0 0] [0 3 4])))
  (is (near3? [0.6 0.8 0] (m/vnorm [3 4 0])))
  (testing "a zero vector stays zero"
    (is (= [0 0 0] (m/vnorm [0 0 0]))))
  (is (= [0.5 1 1.5] (m/lerp3 [0 0 0] [1 2 3] 0.5))))

(deftest easing-starts-at-0-and-ends-at-1
  (doseq [f [m/ease-out-cubic m/ease-in-out-quad m/ease-in-out-cubic]]
    (is (near? 0 (f 0)))
    (is (near? 1 (f 1)))
    (is (< 0 (f 0.3) 1)))
  (is (near? 0.5 (m/ease-in-out-quad 0.5)))
  (is (near? 0.5 (m/ease-in-out-cubic 0.5))))

(deftest progress-is-clamped
  (is (= 0 (m/progress 50 100 200)))
  (is (= 0.5 (m/progress 200 100 200)))
  (is (= 1 (m/progress 900 100 200))))

(deftest the-camera
  (testing "a ball fills a 60 degree view at 1.9 radii (0.95 / sin 30)"
    (is (near? 1.9 (m/fit-distance 1 60))))
  (is (near3? [0 0 1] (m/orbit-dir 0 0)))
  (is (near3? [1 0 0] (m/orbit-dir 90 0)))
  (is (near3? [0 1 0] (m/orbit-dir 0 90)))
  (is (= {:left -2.3 :right 2.3 :top 2.3 :bottom -2.3 :near 0.1 :far 10} (m/sun-frame 2))))

(deftest the-script-clock
  (testing "no step on the first frame"
    (is (= 5 (m/advance-clock 5 0 1000 1))))
  (is (near? 5.05 (m/advance-clock 5 1000 1050 1)))
  (testing "a long pause counts as 0.1 s, times the rate"
    (is (near? 5.2 (m/advance-clock 5 1000 9000 2)))))

(deftest dims-ease-then-snap
  (is (near? 0.8 (m/dim-step 1 0)))
  (is (= 0.16 (m/dim-step 0.165 0.16)))
  (is (nil? (m/dim-step 1 1))))

(deftest wheels
  (is (= :pinch (m/wheel-kind {:mode 0 :dx 0 :dy 3.5 :ctrl true})))
  (is (= :mouse (m/wheel-kind {:mode 1 :dx 0 :dy 3 :ctrl false})))
  (is (= :mouse (m/wheel-kind {:mode 0 :dx 0 :dy 100 :ctrl false})))
  (is (= :pan (m/wheel-kind {:mode 0 :dx 2 :dy 100 :ctrl false})))
  (is (= :pan (m/wheel-kind {:mode 0 :dx 0 :dy 12.5 :ctrl false})))
  (is (near? (js/Math.exp 0.1) (m/zoom-factor :mouse 120)))
  (is (near? (js/Math.exp -1.2) (m/zoom-factor :pinch -100)))
  (is (= 80 (m/dolly-distance 2 50)))
  (is (= 0.5 (m/dolly-distance 0.1 1)))
  (is (near? (/ 2 100) (m/pan-per-pixel 1 90 100))))

(deftest clicks-and-picks
  (is (m/click? [10 10] 12 13 0))
  (is (not (m/click? [10 10] 20 10 0)))
  (is (not (m/click? [10 10] 10 10 2)))
  (is (not (m/click? nil 10 10 0)))
  (is (= [0 0] (m/ndc 50 25 {:left 0 :top 0 :width 100 :height 50})))
  (is (= [-1 1] (m/ndc 10 20 {:left 10 :top 20 :width 100 :height 50})))
  (is (= [0 0] (first m/pick-offsets))))

(deftest arrows
  (let [pts [[0 0 0] [0 1 0] [1 1 0]]]
    (is (= 2 (m/path-length pts)))
    (is (= {:p [0 0.5 0] :d [0 1 0]} (m/along pts 0.5)))
    (is (= {:p [0.5 1 0] :d [1 0 0]} (m/along pts 1.5)))
    (testing "past the end it stays on the last piece"
      (is (= [1 1 0] (:p (m/along pts 9)))))
    (is (= {:p [0 0 0] :d m/up} (m/along [[0 0 0]] 1)))
    (is (= 0.4 (m/arrow-head 2)))
    (is (near? 0.35 (m/arrow-head 1)))
    (testing "the last segment stops where the head begins"
      (is (= [{:pos [0 0.5 0] :len 1 :dir [0 1 0]} {:pos [0.3 1 0] :len 0.6 :dir [1 0 0]}]
             (m/arrow-segments pts 0.4))))
    (is (= {:pos [0.8 1 0] :dir [1 0 0]} (m/arrow-tip pts 0.4)))
    (testing "dashes spread along the path and run on with time"
      (is (= {:pos [0 0 0] :dir [0 1 0]} (m/dash-at pts 2 0.4 0 4 0)))
      (is (near3? [0 0.4 0] (:pos (m/dash-at pts 2 0.4 1 4 0))))
      (is (near3? [0 0.48 0] (:pos (m/dash-at pts 2 0.4 0 4 1)))))))

(deftest two-bone-chains
  (testing "never quite straight, never folded tighter than the bones allow"
    (is (near? 0.9995 (m/chain-reach 0.5 0.5 3)))
    (is (near? 0.201 (m/chain-reach 0.5 0.3 0)))
    (is (= 0.7 (m/chain-reach 0.5 0.5 0.7))))
  (is (near? (/ js/Math.PI 3) (m/bend-angle 1 1 1)))
  (is (near? 0 (m/bend-angle 1 1 2))))
