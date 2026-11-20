(ns claudia.web.views.pure.viewer-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.viewer :as pv]))

(deftest step-stays-inside
  (is (= 1 (pv/step 0 3 1)))
  (is (= 1 (pv/step 2 3 -1)))
  (is (nil? (pv/step 2 3 1)))
  (is (nil? (pv/step 0 3 -1)))
  (is (nil? (pv/step 0 1 1))))

(deftest swipes
  (is (= :next (pv/swipe-outcome -120 10 1000 3)))
  (is (= :prev (pv/swipe-outcome 120 -10 1000 3)))
  (is (nil? (pv/swipe-outcome -120 10 1000 1)) "one item: sideways does nothing")
  (is (nil? (pv/swipe-outcome -60 0 1000 3)) "not far enough")
  (is (= :next (pv/swipe-outcome -70 0 300 3)) "a narrow window needs a fifth of its width")
  (is (= :close (pv/swipe-outcome 20 150 1000 3)))
  (is (nil? (pv/swipe-outcome 0 -200 1000 3)) "up never closes")
  (is (nil? (pv/swipe-outcome 100 120 1000 1)) "down but too much sideways"))

(deftest drag-follows-one-direction
  (is (= [-50 0] (pv/drag-offset -50 10 2)))
  (is (= [-15 0] (pv/drag-offset -45 10 1)))
  (is (= [0 80] (pv/drag-offset 10 80 2)))
  (is (= [0 -10] (pv/drag-offset 0 -30 2))))

(deftest path-of-a-step
  (is (= "/tmp/shot one.png" (pv/path-in "{\n  \"path\": \"/tmp/shot one.png\",\n  \"caption\": \"x\"\n}")))
  (is (= "C:\\a\\b.mp4" (pv/path-in "{\"path\": \"C:\\\\a\\\\b.mp4\"}")))
  (is (nil? (pv/path-in "{\"caption\": \"x\"}")))
  (is (nil? (pv/path-in nil))))
