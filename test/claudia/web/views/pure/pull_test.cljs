(ns claudia.web.views.pure.pull-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.pull :as pull]))

(deftest the-circles-follow-half-the-finger-up-to-a-limit
  (is (= 0 (pull/distance -40)))
  (is (= 20 (pull/distance 40)))
  (is (= pull/max-px (pull/distance 1000))))

(deftest letting-go-far-enough-reloads
  (is (not (pull/ready? (pull/distance 100))))
  (is (pull/ready? (pull/distance 128))))
