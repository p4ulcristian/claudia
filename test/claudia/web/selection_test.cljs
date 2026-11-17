(ns claudia.web.selection-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.selection :as selection]))

(deftest a-let-go-selection-reports-what-disturbed-it
  (is (= {:event "selection" :ms 2500 :chars 40 :error "self-scrolls held back 3, column changes 12"}
         (selection/report-of {:at 1000 :chars 40 :held 3 :changes 12} 3500))))
