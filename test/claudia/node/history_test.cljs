(ns claudia.node.history-test
  (:require ["node:sqlite" :refer [DatabaseSync]]
            [cljs.test :refer [deftest is testing]]
            [claudia.node.history :as history]))

(def top
  [{:pid 1 :name "a" :cores 3 :rss 10 :owner {:kind "unit" :name "a.service"}}
   {:pid 2 :name "b" :cores 2 :rss 20 :owner {:kind "other"}}
   {:pid 3 :name "c" :cores 1 :rss 900 :owner {:kind "other"}}
   {:pid 4 :name "d" :cores 0.5 :rss 30 :owner {:kind "other"}}
   {:pid 5 :name "e" :cores 0 :rss 800 :owner {:kind "other"}}
   {:pid 6 :name "f" :cores 0 :rss 700 :owner {:kind "other"}}])

(deftest a-rows-top-is-3-busiest-then-2-biggest
  (is (= ["a" "b" "c" "e" "f"] (mapv :name (history/row-top top))))
  (is (= {:name "a" :owner {:kind "unit" :name "a.service"} :cores 3 :rss 10} (first (history/row-top top)))))

(deftest a-row-from-a-minutes-sample
  (let [s {:at 60000 :cpu {:all {:busy 25} :cores [{} {} {} {}]}
           :memory {:used 5 :total 10 :swapUsed 1}
           :disks [{:used 50 :avail 50} {:used 90 :avail 10}]
           :gpus [{:memUsed 100} {:memUsed 200}] :top top}]
    (is (= {:at 60000 :cores 1 :coresTotal 4 :mem 5 :memTotal 10 :swap 1 :disk 90 :gpuMem 300
            :pressure {:cpu 1.5 :memory 0 :io 2}
            :top (history/row-top top)}
           (history/row-of s {:cpu {:some 1.5 :full nil} :memory {:some 0 :full 0} :io {:some 2 :full 1}})))
    (testing "no PSI, no cards: left out"
      (is (not-any? (set (keys (history/row-of (assoc s :gpus []) nil))) [:pressure :gpuMem])))))

(deftest the-hours-asked
  (is (= [24 1 168 6] (mapv history/hours-of [nil "0" "999" "6"]))))

(deftest rows-kept-and-pruned
  (let [db (DatabaseSync. ":memory:")]
    (history/open-table! db)
    (doseq [at [1000 2000 3000]] (history/save! db {:at at :cores at}))
    (history/prune! db 1500)
    (is (= [{:at 3000 :cores 3000}] (history/rows-since db 2500)))
    (is (= 2 (count (history/rows-since db 0))))
    (is (= {:step 60000 :rows [] :events [{:at 5}]} (history/answer [] [{:at 5} {:at 1}] 3)))))
