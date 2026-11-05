(ns claudia.web.download-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.download :as download]))

(deftest a-title-as-a-file-name
  (is (= "Electricity-built-up.mp4" (download/file-name "Electricity, built up" "scene" "mp4")))
  (is (= "Árvíztűrő-tükör.png" (download/file-name " Árvíztűrő / tükör " "picture" "png")))
  (is (= "scene.webm" (download/file-name nil "scene" "webm")))
  (is (= "picture.png" (download/file-name "!!" "picture" "png"))))
