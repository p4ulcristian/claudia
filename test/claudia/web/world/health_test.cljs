(ns claudia.web.world.health-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.world.health :as health]))

(defn- sample [cpu used gpu]
  {:cpu {:all {:busy cpu}} :memory {:used used :total 100} :gpus (if gpu [{:util gpu}] [])})

(deftest load-in-percent
  (is (= {:cpu 12 :mem 40 :gpu 7} (health/load-of (sample 12.4 40 7))))
  (is (nil? (:gpu (health/load-of (sample 1 1 nil)))) "no GPU")
  (is (nil? (health/load-of nil))))

(deftest one-word-per-server
  (is (= :free (health/status {:sample (sample 10 30 nil)})))
  (is (= :busy (health/status {:sample (sample 60 30 nil)})))
  (is (= :busy (health/status {:sample (sample 10 80 nil)})))
  (is (= :overworked (health/status {:sample (sample 10 30 95)})))
  (is (= :overworked (health/status {:sample (sample 90 30 nil)})))
  (is (= :dead (health/status {:error "502"})))
  (is (= :free (health/status {:sample (sample 10 30 nil) :error "blip" :age 3000})) "a recent sample still counts")
  (is (= :dead (health/status {:sample (sample 10 30 nil) :error "502" :age 9000})))
  (is (= :asking (health/status {}))))
