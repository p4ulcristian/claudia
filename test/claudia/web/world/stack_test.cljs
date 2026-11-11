(ns claudia.web.world.stack-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.world.stack :as stack]))

(defn- box [x y] {:x x :y y :hw 50 :hh 30})

(deftest apart-boxes-stay
  (is (= [[0 0] [0 0]] (stack/moves [(box 0 0) (box 200 0)] 4))))

(deftest a-box-mostly-to-the-side-moves-sideways
  (is (= [[0 0] [34 0]] (stack/moves [(box 0 0) (box 70 0)] 4))))

(deftest a-box-right-behind-rises
  (is (= [[0 0] [0 64]] (stack/moves [(box 0 0) (box 0 0)] 4))))

(deftest many-boxes-spread-without-overlap
  (let [boxes (repeat 7 (box 0 0))
        placed (map (fn [b [dx dy]] (-> b (update :x + dx) (update :y + dy))) boxes (stack/moves boxes 4))]
    (is (= 7 (count (distinct (map (juxt :x :y) placed)))))
    (is (every? (fn [[a b]] (or (>= (js/Math.abs (- (:x a) (:x b))) 104) (>= (js/Math.abs (- (:y a) (:y b))) 64)))
                (for [a placed b placed :when (not (identical? a b))] [a b])))))
