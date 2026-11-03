(ns claudia.web.subs-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.subs :as subs]))

(def ^:private servers
  {"a" {:status :open :rows {"1" {:id "1" :started 2} "2" {:id "2" :started 1 :archived true}}}
   "b" {:status :closed :rows {"3" {:id "3" :started 0}}}})

(deftest list-rows-in-start-order
  (is (= [{:id "3" :started 0 :server "b" :off true} {:id "1" :started 2 :server "a" :off false}]
         (subs/list-rows servers false #{})))
  (is (= ["2"] (map :id (subs/list-rows servers true #{}))))
  (is (= ["1"] (map :id (subs/list-rows servers false #{"b"}))))
  (is (= ["3" "2" "1"] (map :id (subs/list-rows servers :any #{})))))
