(ns claudia.node.env-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.node.env :refer [env]]))

(deftest env-is-trimmed-and-blank-is-unset
  (aset js/process.env "CLAUDIA_TEST_ENV" "  value \n")
  (is (= "value" (env "CLAUDIA_TEST_ENV")))
  (aset js/process.env "CLAUDIA_TEST_ENV" "   ")
  (is (nil? (env "CLAUDIA_TEST_ENV")))
  (is (= "fallback" (env "CLAUDIA_TEST_ENV" "fallback")))
  (js-delete js/process.env "CLAUDIA_TEST_ENV")
  (is (= "fallback" (env "CLAUDIA_TEST_ENV" "fallback"))))
