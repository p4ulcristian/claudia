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

(deftest a-page-never-asks-a-front-for-its-machine-reads
  (doseq [p ["/api/system/details" "/api/system/history" "/api/system/unit-log"]]
    (is (= {:status 404 :error "not found"} (main/path-problem p false nil nil))))
  (is (nil? (main/path-problem "/api/system/proposals" false nil nil))))

(deftest labels-are-checked
  (is (= {:server "*" :kind "port" :match "5432" :project "Shop" :by "a@x"}
         (systems/label-of {:server "*" :kind "port" :match " 5432 " :project "Shop"} "a@x")))
  (is (= "home" (:server (systems/label-of {:server "home" :kind "unit" :match "a.service" :project "p"} "a@x"))))
  (doseq [bad [{:server "Home!" :kind "unit" :match "a" :project "p"}
               {:server "home" :kind "file" :match "a" :project "p"}
               {:server "home" :kind "unit" :match "" :project "p"}
               {:server "home" :kind "unit" :match "a" :project (apply str (repeat 121 "x"))}]]
    (is (:error (systems/label-of bad "a@x")) (pr-str bad))))
