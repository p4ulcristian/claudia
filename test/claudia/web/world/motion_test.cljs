(ns claudia.web.world.motion-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.world.motion :as motion]))

(defn- near? [a b] (< (js/Math.abs (- a b)) 1e-6))

(defn- run-spring
  "x, v after n steps of dt toward target, capped like a robot."
  [x target n dt]
  (reduce (fn [[x0 v0] _]
            (let [v1 (motion/spring-velocity x0 v0 target dt)
                  [vc] (motion/cap-speed v1 0)]
              [(+ x0 (* vc dt)) vc]))
          [x 0] (range n)))

(deftest the-spring-arrives-and-overshoots-a-little
  (let [path (map #(first (run-spring 0 1 % (/ 1 60))) (range 600))]
    (is (near? 1 (last path)))
    (is (< 1 (apply max path) 1.1) "a small overshoot, no wild swing")))

(deftest speed-is-capped-keeping-direction
  (let [[vx vz] (motion/cap-speed 30 40)]
    (is (near? motion/top-speed (js/Math.hypot vx vz)))
    (is (near? (/ 3 4) (/ vx vz))))
  (is (= [1 1] (motion/cap-speed 1 1))))

(deftest angles-take-the-short-way
  (is (near? 0 (motion/wrap-angle (* 2 js/Math.PI))))
  (is (near? (- js/Math.PI 0.1) (motion/wrap-angle (+ (- js/Math.PI 0.1) (* 4 js/Math.PI)))))
  (testing "from just under pi to just over -pi is a small step forward"
    (is (> (motion/damp-angle 3.1 -3.1 5 0.1) 3.1))))

(deftest damping-is-frame-rate-independent
  (let [once (motion/damp 0 1 4 0.5)
        twice (motion/damp (motion/damp 0 1 4 0.25) 1 4 0.25)]
    (is (near? once twice))))

(deftest heading-follows-motion-else-rests
  (is (near? 0 (motion/heading 0 1 2)))
  (is (near? (/ js/Math.PI 2) (motion/heading 1 0 2)))
  (is (= 2 (motion/heading 0.1 0.1 2))))

(deftest lean-is-capped
  (is (= [0.32 -0.35] (motion/lean 100 100)))
  (is (= [0 0] (motion/lean 0 0))))

(deftest robots-hover-by-state
  (is (= 0.6 (motion/hover :off 3 0)))
  (is (< 1.1 (motion/hover :idle 3 0) 1.4)))

(deftest blinks-are-short
  (let [samples (map #(motion/blink (* % 0.01) 0) (range 1000))]
    (is (< (count (filter #(< % 0.5) samples)) 60))
    (is (= 1 (motion/blink 1 0)))))
