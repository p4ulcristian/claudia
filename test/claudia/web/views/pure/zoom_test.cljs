(ns claudia.web.views.pure.zoom-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.zoom :as z]))

(deftest zoom-keeps-the-point-still
  (let [zz (z/zoom-at z/none 2 100 50)]
    (is (= 2 (:k zz)))
    ;; the image point under (100, 50) was 100, 50 from its centre; at k 2 it lands at 200 + x
    (is (= 100 (+ (:x zz) (* 2 100))))
    (is (= 50 (+ (:y zz) (* 2 50)))))
  (is (= z/max-k (:k (z/zoom-at z/none 100 0 0))))
  (is (= 1 (:k (z/zoom-at z/none 0.1 0 0)))))

(deftest kept-in-the-window
  (is (= z/none (z/kept-in {:k 1.005 :x 30 :y 9} [400 300] [800 600])))
  (is (= {:k 4 :x 400 :y -300} (z/kept-in {:k 4 :x 999 :y -999} [400 300] [800 600])))
  (is (= {:k 2 :x 0 :y 0} (z/kept-in {:k 2 :x 50 :y 50} [400 300] [800 600]))))

(deftest pinch-zooms-and-moves
  (let [zz (z/pinched z/none [[-10 0] [10 0]] [[-20 0] [20 0]])]
    (is (= 2 (:k zz)))
    (is (= 0 (:x zz))))
  (is (= 5 (:x (z/pinched z/none [[-10 0] [10 0]] [[-5 0] [15 0]])))))

(deftest double-tap-toggles
  (is (= z/tap-k (:k (z/double-tapped z/none 0 0))))
  (is (= z/none (z/double-tapped {:k 3 :x 1 :y 1} 0 0))))

(deftest wheel-and-css
  (is (> (z/wheel-factor -100 false) 1))
  (is (< (z/wheel-factor 100 true) 1))
  (is (= "translate(1px, 2px) scale(3)" (z/transform {:k 3 :x 1 :y 2}))))
