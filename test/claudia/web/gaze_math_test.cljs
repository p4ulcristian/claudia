(ns claudia.web.gaze-math-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.gaze.math :as m]))

(defn- near? [a b eps] (< (js/Math.abs (- a b)) eps))

(deftest solve-test
  (is (= [1 2] (mapv #(js/Math.round %) (m/solve [[2 1] [1 3]] [4 7]))))
  (testing "a singular matrix has no answer"
    (is (nil? (m/solve [[1 2] [2 4]] [1 2])))))

(defn- reading [hx hy] {:hx hx :hy hy :yaw 0.01 :pitch -0.02 :x 0 :y 0 :z -0.4 :blink 0.1})

(deftest features-test
  (is (= 10 (count (m/features (reading 0.1 0.2)))))
  (testing "shut eyes and missing numbers give nothing"
    (is (nil? (m/features (assoc (reading 0 0) :blink 0.9))))
    (is (nil? (m/features (dissoc (reading 0 0) :hx))))
    (is (nil? (m/features (assoc (reading 0 0) :hy js/NaN))))))

(defn- samples
  "Samples where the window point is a straight function of the ray's hit (with a tiny wobble)."
  []
  (for [[x y] m/dots k (range 5)
        :let [w (* 0.0005 (- k 2))]]
    {:f (m/features (reading (+ (* 0.2 x) -0.1 w) (- 0.15 (* 0.3 y) w))) :at [x y]}))

(deftest fit-test
  (let [model (m/fit (samples))]
    (is (some? model))
    (is (< (m/error model (samples)) 0.01))
    (let [[x y] (m/predict model (m/features (reading 0 0)))]
      (is (near? x 0.5 0.02))
      (is (near? y 0.5 0.02))))
  (testing "too few samples make no model"
    (is (nil? (m/fit (take 5 (samples)))))))

(deftest cal-moment-test
  (is (= {:i 0 :p 0 :sample? false} (m/cal-moment 1000 1000)))
  (is (:sample? (m/cal-moment 0 (+ m/settle-ms 1))))
  (is (= 1 (:i (m/cal-moment 0 (+ m/dot-ms 10)))))
  (is (:done? (m/cal-moment 0 (* m/dot-ms (count m/dots))))))

(deftest one-euro-test
  (let [s0 (m/one-euro nil 0.5 0 m/one-euro-params)
        s1 (m/one-euro s0 0.6 0.033 m/one-euro-params)]
    (is (= 0.5 (:x s0)))
    (is (< 0.5 (:x s1) 0.6) "moves towards the new value, not all the way")))

(deftest step-test
  (let [readings (for [[i [x y]] (map-indexed vector m/dots) k (range 16)]
                   [(+ (* i m/dot-ms) (* k 100)) (reading (+ (* 0.2 x) -0.1) (- 0.15 (* 0.3 y)))])
        st (reduce (fn [st [t r]] (m/step st r t)) (m/calibrating 0) readings)
        done (m/step st (reading 0 0) (* m/dot-ms (count m/dots)))]
    (testing "a calibration shows dots, then learns a model"
      (is (= :dot (:kind (:show (m/step (m/calibrating 0) (reading 0 0) 10)))))
      (is (= :tracking (:phase done))))
    (testing "tracking shows the pointer, and keeps it (lost) when the eyes shut"
      (let [t1 (m/step done (reading 0 0) 20000)]
        (is (= :pointer (:kind (:show t1))))
        (is (:lost? (:show (m/step t1 (assoc (reading 0 0) :blink 1) 20033))))))
    (testing "a calibration without readings fails"
      (is (= :failed (:phase (m/step (m/calibrating 0) nil 20000)))))))
