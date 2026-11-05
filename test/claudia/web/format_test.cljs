(ns claudia.web.format-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.format :as fmt]))

(deftest a-duration
  (is (= "0:00" (fmt/duration 0)))
  (is (= "0:09" (fmt/duration 9.7)))
  (is (= "1:05" (fmt/duration 65)))
  (is (= "12:00" (fmt/duration 720)))
  (is (= "0:00" (fmt/duration -3))))
