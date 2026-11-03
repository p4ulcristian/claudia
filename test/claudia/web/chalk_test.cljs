(ns claudia.web.chalk-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [claudia.web.chalk :as chalk]))

(deftest lcg-is-park-miller
  (is (= 16807 (chalk/lcg 1)))
  (is (= 282475249 (chalk/lcg 16807)))
  (is (= 1 (chalk/start-seed 1)))
  (is (= 2147483646 (chalk/start-seed 3000000000)))
  (is (= 7 (chalk/start-seed 7))))

(deftest turbulence-is-the-same-for-a-seed
  (let [a (chalk/make-turbulence 3)
        b (chalk/make-turbulence 3)
        c (chalk/make-turbulence 5)]
    (is (= (vec (:lattice a)) (vec (:lattice b))))
    (is (= (vec (aget (:grads a) 2)) (vec (aget (:grads b) 2))))
    (is (not= (vec (:lattice a)) (vec (:lattice c))))
    (testing "the lattice's first 256 places are a permutation, repeated after"
      (is (= (range 256) (sort (take 256 (:lattice a)))))
      (is (= (take 258 (:lattice a)) (take 258 (drop 256 (:lattice a))))))
    (testing "gradients are unit length"
      (let [g (aget (:grads a) 0)]
        (is (< (js/Math.abs (- 1 (js/Math.hypot (aget g 0) (aget g 1)))) 1e-9))))))

(deftest shape-alpha-inside-and-outside
  (let [spec (chalk/shape-spec 40 {:r 6})]
    (is (= 18 (:half spec)))
    (is (= 6 (:r spec)))
    (is (= 1 (chalk/shape-alpha spec 20 20)))
    (is (nil? (chalk/shape-alpha spec 0 0))))
  (testing "a ring is left clear in the middle"
    (is (nil? (chalk/shape-alpha (chalk/shape-spec 40 {:r 6 :b 2}) 20 20))))
  (is (= 2 (:r (chalk/shape-spec 8 {:r 9})))))

(deftest grain-alpha-stays-in-range
  (let [turb (chalk/make-turbulence 14)
        as (for [x (range 0 48 7) y (range 0 48 7)] (chalk/grain-alpha turb 0.85 0.24 0.06 x y))]
    (is (every? #(<= 0 % 1) as))
    (is (some pos? as))))

(deftest grain-mask-names-its-tile
  (let [svg (chalk/grain-mask "g" "data:x")]
    (is (str/includes? svg "<pattern id=\"g-tile\""))
    (is (str/includes? svg "href=\"data:x\""))
    (is (str/includes? svg "fill=\"url(#g-tile)\""))))
