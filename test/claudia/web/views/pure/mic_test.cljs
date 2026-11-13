(ns claudia.web.views.pure.mic-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.mic :as mic]))

(deftest heard-text-joins-the-draft
  (is (= "hello" (mic/with-heard "" " hello ")))
  (is (= "fix it please" (mic/with-heard "fix it" "please")))
  (is (= "line\nnext" (mic/with-heard "line\n" "next")))
  (is (= "kept" (mic/with-heard "kept" "  ")))
  (is (= "x" (mic/with-heard nil "x"))))

(deftest bars-are-mirrored-levels
  (let [levels (mic/bar-levels (vec (repeat 256 255)))]
    (is (= mic/bar-count (count levels)))
    (is (every? #(= 1 %) levels)))
  (let [levels (mic/bar-levels (vec (concat (repeat 8 255) (repeat 248 0))))]
    (is (= (first levels) (last levels)))
    (is (pos? (first levels)))
    (is (zero? (nth levels 20)))))

(deftest bars-ease
  (is (= [0.6 0.8] (mic/eased [0 1] [1 0])))
  (is (= [0.6] (mic/eased nil [1]))))
