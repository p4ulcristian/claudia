(ns claudia.hub.progress-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.progress :as p]))

(def c (apply str (repeat 40 "a")))

(deftest builds-keep-one-line-per-commit-newest-first
  (let [bs (-> [] (p/with-build {:commit c :state "pending" :description "checking"} 100)
               (p/with-build {:commit c :tree "t" :state "success" :description "done in 60 s"} 160))]
    (is (= 1 (count bs)))
    (is (= {:commit c :state "success" :note "done in 60 s" :at 160 :tree "t" :started 100} (first bs)))))

(deftest a-release-follows-every-machine-and-the-hub
  (let [r (p/new-release c "t" "Fix" ["box" "destiny"] 1000)]
    (is (not (p/settled? r)))
    (let [r2 (-> r (p/machine-done "box" 5000) (p/machine-done "destiny" 7000) (p/hub-done 16000))]
      (is (p/settled? r2))
      (is (= 4 (get-in r2 [:machines "box" :secs])))
      (is (= 15 (get-in r2 [:hub :secs]))))
    (testing "what still waits after the limit is not reached"
      (is (= "not reached" (get-in (p/given-up (p/machine-done r "box" 2000)) [:machines "destiny" :state])))
      (is (= "done" (get-in (p/given-up (p/machine-done r "box" 2000)) [:machines "box" :state]))))))

(deftest machines-name-seven-characters
  (is (p/short= c "aaaaaaa"))
  (is (not (p/short= c "bbbbbbb")))
  (is (not (p/short= c nil))))
