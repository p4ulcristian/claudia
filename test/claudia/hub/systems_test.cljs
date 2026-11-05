(ns claudia.hub.systems-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.main :as main]
            [claudia.hub.systems :as systems]))

(deftest a-front-answer-becomes-the-page-answer
  (is (= [200 {:at 1}] (systems/answer-of [200 {:at 1}])))
  (is (= [502 {:error "not reached: connect ETIMEDOUT"}] (systems/answer-of [0 {:error "connect ETIMEDOUT"}])))
  (is (= [502 {:error "answered 500: boom"}] (systems/answer-of [500 {:error "boom"}]))))

(deftest a-page-never-asks-a-front-for-its-numbers
  (is (= {:status 404 :error "not found"} (main/path-problem "/api/system" false nil nil))))
