(ns claudia.web.rowdiff-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.rowdiff :as rowdiff]))

(defn- db [rows] {:servers {"a" {:rows rows}}})

(deftest turns-move
  (is (= [[:started ["a" "1"] {:running true}]]
         (vec (rowdiff/row-events (db {"1" {}}) (db {"1" {:running true}})))))
  (is (= [:done] (map first (rowdiff/row-events (db {"1" {:running true}}) (db {"1" {}})))))
  (is (= [:waiting] (map first (rowdiff/row-events (db {"1" {:running true}}) (db {"1" {:running true :waiting true}})))))
  (is (= [:gone] (map first (rowdiff/row-events (db {"1" {}}) (db {"1" {:archived true}}))))))

(deftest arrivals-and-departures
  (testing "a new chat on a loaded list"
    (is (= [:new] (map first (rowdiff/row-events (db {"1" {}}) (db {"1" {} "2" {}}))))))
  (testing "the first load of a list is no crowd of new chats"
    (is (empty? (rowdiff/row-events {:servers {}} (db {"1" {} "2" {}})))))
  (testing "a removed chat"
    (is (= [[:gone ["a" "2"] {}]] (vec (rowdiff/row-events (db {"1" {} "2" {}}) (db {"1" {}})))))))
