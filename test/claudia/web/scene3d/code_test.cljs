(ns claudia.web.scene3d.code-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.scene3d.code :as code]))

(deftest ranges-and-marks
  (is (= [[4 8] [15 19]] (code/spans ["15-19" "4-8"])))
  (is (= [[3 3]] (code/spans 3)))
  (is (= [[2 5]] (code/spans "5-2")))
  (is (= [] (code/spans nil)))
  (is (= [] (code/spans ["x" 0])))
  (is (= #{1 3 4 5} (code/marked [1 "3-5"]))))

(deftest rows-with-gaps
  (let [lines ["a" "b" "c" "d" "e" "f"]]
    (is (= [[1 "a"] [2 "b"] [3 "c"] [4 "d"] [5 "e"] [6 "f"]] (code/rows lines 1 [])))
    (is (= [[2 "b"] [3 "c"] nil [6 "f"]] (code/rows lines 1 [[2 3] [6 9]])))
    (is (= [[11 "b"]] (code/rows lines 10 [[11 11] [40 50]])))))

(deftest language-and-size
  (is (= "clj" (code/lang-of nil "a/read.clj")))
  (is (= "sql" (code/lang-of "" "x/Q.SQL")))
  (is (= "clojure" (code/lang-of "clojure" "x.sql")))
  (is (nil? (code/lang-of nil "Makefile")))
  (let [{:keys [font width height]} (code/layout [[1 (apply str (repeat 200 "x"))]] 0.6)]
    (is (= code/canvas-width width))
    (is (< font 20))
    (is (> height code/head)))
  (is (= 40 (:font (code/layout [[1 "short"]] 0.6)))))
