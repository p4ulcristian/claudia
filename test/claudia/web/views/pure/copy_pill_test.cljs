(ns claudia.web.views.pure.copy-pill-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.copy-pill :as pill]))

(deftest the-pill-sits-above-the-selection
  (is (= {:x 266 :y 130} (pill/position {:left 200 :top 200 :right 400 :bottom 220} 1000))
      "centred, above by the gap")
  (is (= {:x 266 :y 250} (pill/position {:left 200 :top 40 :right 400 :bottom 220} 1000))
      "no room above: below by the gap")
  (is (= 8 (:x (pill/position {:left 0 :top 200 :right 10 :bottom 220} 1000))) "kept off the left edge")
  (is (= 924 (:x (pill/position {:left 990 :top 200 :right 1000 :bottom 220} 1000))) "and the right"))
