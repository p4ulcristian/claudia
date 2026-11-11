(ns claudia.web.world.look-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.world.look :as look]))

(deftest materials-to-kinds
  (is (= [1 2 3 3 3 0 0] (map look/kind ["grass" "window" "lamp" "eyes" "jet" "wall" nil]))))
