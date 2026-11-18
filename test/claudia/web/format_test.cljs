(ns claudia.web.format-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.format :as fmt]))

(deftest a-duration
  (is (= "0:00" (fmt/duration 0)))
  (is (= "0:09" (fmt/duration 9.7)))
  (is (= "1:05" (fmt/duration 65)))
  (is (= "12:00" (fmt/duration 720)))
  (is (= "0:00" (fmt/duration -3))))

(deftest sizes-in-binary-units
  (is (= "512 B" (fmt/bytes-text 512)))
  (is (= "1.5 KiB" (fmt/bytes-text 1536)))
  (is (= "13.1 GiB" (fmt/bytes-text (* 13.1 1024 1024 1024))))
  (is (= "950 GiB" (fmt/bytes-text (* 950 1024 1024 1024)))))
