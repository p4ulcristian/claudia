(ns claudia.web.gaze-math-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.gaze.math :as m]))

(defn- near? [a b eps] (< (js/Math.abs (- a b)) eps))

(deftest solve-test
  (is (= [1 2] (mapv #(js/Math.round %) (m/solve [[2 1] [1 3]] [4 7]))))
  (testing "a singular matrix has no answer"
    (is (nil? (m/solve [[1 2] [2 4]] [1 2])))))

(defn- reading
  "A reading whose nose line meets the screen's plane at [hx hy] (head 40 cm away)."
  [hx hy]
  (let [z -0.4 t 0.4]
    {:x 0 :y 0 :z z :yaw (js/Math.atan2 hx t) :pitch (js/Math.asin (/ hy (js/Math.hypot hx hy t)))
     :blink 0.9}))

(deftest head-hit-test
  (let [[x y] (m/head-hit (reading 0.05 -0.03))]
    (is (near? x 0.05 1e-6))
    (is (near? y -0.03 1e-6)))
  (is (nil? (m/head-hit (dissoc (reading 0 0) :yaw))))
  (is (some? (m/features (reading 0 0))) "blinks don't matter to the head"))

(defn- look [[x y]] (reading (+ (* 0.2 x) -0.1) (- 0.15 (* 0.3 y))))

(defn- samples
  "Samples where the window point is a straight function of the nose line's hit (with a tiny wobble)."
  []
  (for [[i d] (map-indexed vector m/dots) k (range 8)
        :let [w (* 0.0005 (- k 4))]]
    {:f (m/features (look (mapv + d [w w]))) :at d :i i}))

(deftest fit-test
  (let [model (m/fit (samples))]
    (is (some? model))
    (is (< (m/error model (samples)) 0.01))
    (let [[x y] (m/predict model (m/features (look [0.3 0.7])))]
      (is (near? x 0.3 0.02))
      (is (near? y 0.7 0.02))))
  (testing "too few samples make no model"
    (is (nil? (m/fit (take 5 (samples)))))))

(deftest dots-test
  (is (= 5 (count m/dots)))
  (is (= [0.5 0.5] (first m/dots))))

(deftest cal-moment-test
  (let [order (vec (range (count m/dots)))]
    (is (= {:i 0 :p 0 :sample? false} (m/cal-moment order 1000 1000)))
    (is (:sample? (m/cal-moment order 0 (+ m/settle-ms 1))))
    (is (= 1 (:i (m/cal-moment order 0 (+ m/dot-ms 10)))))
    (is (= 3 (:i (m/cal-moment [3 2] 0 10))) "a redo shows only the dots it names")
    (is (:done? (m/cal-moment order 0 (* m/dot-ms (count m/dots)))))))

(deftest cleaned-test
  (let [good (vec (samples))
        glance {:f (m/features (look [0.9 0.9])) :at [0.12 0.12] :i 1}]
    (is (= (count good) (count (m/cleaned (conj good glance)))) "a turn away is dropped")))

(deftest one-euro-test
  (let [s0 (m/one-euro nil 0.5 0 m/one-euro-params)
        s1 (m/one-euro s0 0.6 0.033 m/one-euro-params)]
    (is (= 0.5 (:x s0)))
    (is (< 0.5 (:x s1) 0.6) "moves towards the new value, not all the way")))

(deftest tapped-test
  (let [st {:raw [0.4 0.4] :offset [0 0]}
        t1 (m/tapped st [0.5 0.5])]
    (is (near? 0.06 (first (:offset t1)) 1e-9) "a tap corrects part of the miss")
    (is (= st (m/tapped st [0.95 0.95])) "a tap far from the raw point teaches nothing")))

(deftest step-test
  (let [readings (for [[i d] (map-indexed vector m/dots) k (range 15)]
                   [(+ (* i m/dot-ms) (* k 100)) (look d)])
        st (reduce (fn [st [t r]] (m/step st r t)) (m/calibrating 0) readings)
        done (m/step st (look [0.5 0.5]) (* m/dot-ms (count m/dots)))]
    (testing "setup shows dots, then learns the map"
      (is (= :dot (:kind (:show (m/step (m/calibrating 0) (reading 0 0) 10)))))
      (is (= :tracking (:phase done))))
    (testing "tracking puts the pointer where the head points, and keeps it (lost) without a face"
      (let [t1 (m/step done (look [0.3 0.6]) 30000)
            [x y] (:at (:show t1))]
        (is (= :pointer (:kind (:show t1))))
        (is (and (near? x 0.3 0.03) (near? y 0.6 0.03)))
        (is (:lost? (:show (m/step t1 {:turn 0} 30033))))))
    (testing "a setup without readings fails"
      (is (= :failed (:phase (m/step (m/calibrating 0) nil 30000)))))))
