(ns claudia.node.http-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.http :as http]))

(deftest parse-object-gives-a-map-or-nothing
  (is (= {:text "hi" :n 1} (http/parse-object "{\"text\":\"hi\",\"n\":1}")))
  (is (= {} (http/parse-object "")))
  (is (= {} (http/parse-object "{broken")))
  (is (= {} (http/parse-object "[1,2]")))
  (is (= {} (http/parse-object "null"))))
