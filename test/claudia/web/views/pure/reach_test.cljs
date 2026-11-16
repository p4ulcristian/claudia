(ns claudia.web.views.pure.reach-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.reach :as reach]))

(deftest an-item-already-in-the-middle-stays
  (is (= 0 (reach/centre-offset 450 100 0 1000))))

(deftest an-item-below-the-middle-is-scrolled-up-to-it
  (is (= 300 (reach/centre-offset 750 100 0 1000))))

(deftest an-item-above-the-middle-is-scrolled-down-to-it
  (is (= -300 (reach/centre-offset 260 80 100 1000))))
