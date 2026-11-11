(ns claudia.web.world.health-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.world.health :as health]))

(defn- sample [cpu used gpu]
  {:cpu {:all {:busy cpu}} :memory {:used used :total 100} :gpus (if gpu [{:util gpu}] [])})

(deftest load-in-percent
  (is (= {:cpu 12 :mem 40 :gpu 7} (select-keys (health/load-of (sample 12.4 40 7)) [:cpu :mem :gpu])))
  (is (= {:cores 2 :vram 25 :vram-used 4e9 :vram-total 16e9 :mem-used 40 :mem-total 100}
         (select-keys (health/load-of {:cpu {:all {:busy 1} :cores [{} {}]} :memory {:used 40 :total 100}
                                       :gpus [{:util 3 :memUsed 1e9 :memTotal 8e9} {:util 5 :memUsed 3e9 :memTotal 8e9}]})
                      [:cores :vram :vram-used :vram-total :mem-used :mem-total]))
      "every card's memory summed")
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

(deftest sizes-in-gb
  (is (= "24 / 62 GB" (health/gb-text 24.4e9 62.2e9)))
  (is (= "5.9 / 16 GB" (health/gb-text 5.94e9 16.3e9)))
  (is (nil? (health/gb-text nil 1))))

(deftest a-full-graphics-card-is-overworked
  (is (= :overworked (health/status {:sample {:cpu {:all {:busy 5}} :memory {:used 1 :total 100}
                                              :gpus [{:util 5 :memUsed 95 :memTotal 100}]}}))))
