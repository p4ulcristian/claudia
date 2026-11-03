(ns claudia.web.views.calls-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.calls :as calls]))

(def ^:private demo
  (calls/demo-by-id
   {:shop.orders/place {:file "src/shop/orders.clj" :row 10 :code "(defn place\n  \"Places an order.\"\n  [o]\n  (db/save o))" :calls ["shop.db/save"]}
    "shop.db/save" {:file "src/shop/db.clj" :row 3 :code "(defn save\n  \"Saves it.\"\n  [x] x)"}}))

(deftest short-ids
  (is (= "state-gate/check" (calls/short-id "features.state-gate/check")))
  (is (= "q.sql" (calls/short-id "file:resources/sql/q.sql")))
  (is (calls/same? "orders/place" "shop.orders/place"))
  (is (not (calls/same? "place" "shop.orders/place"))))

(deftest demo-reads-like-the-runner
  (is (= "Places an order." (calls/demo-doc demo "shop.orders/place")))
  (is (= [{:row 13 :col 7 :endCol 11}] (calls/call-spots demo "shop.orders/place" "shop.db/save")))
  (let [v (calls/demo-var demo "orders/place")]
    (is (= "shop.orders/place" (:id v)))
    (is (= 13 (:endRow v)))
    (is (= ["shop.db/save"] (map :id (:callees v))))
    (is (empty? (:callers v))))
  (is (= [{:row 13 :text "(db/save o))"}] (:lines (first (:callers (calls/demo-var demo "shop.db/save"))))))
  (is (= {:error "no var x/y in the demo"} (calls/demo-var demo "x/y"))))

(deftest demo-map-walks-both-ways
  (let [m (calls/demo-map demo "shop.db/save" 2 2)]
    (is (= "shop.db/save" (:start m)))
    (is (= {"shop.db/save" 0 "shop.orders/place" -1} (into {} (map (juxt :id :dist)) (:nodes m))))
    (is (= [["shop.orders/place" "shop.db/save"]] (:edges m)))))

(deftest cut-at-links-splits-pieces
  (let [base [{:text "(db/save o)" :color "c"}]]
    (is (= base (calls/cut-at-links base "(db/save o)" [])))
    (is (= [{:text "(" :color "c" :to nil} {:text "db/save" :color "c" :to "shop.db/save"} {:text " o)" :color "c" :to nil}]
           (calls/cut-at-links base "(db/save o)" [{:start 1 :end 8 :to "shop.db/save"}])))))

(deftest folds-in-long-code
  (is (= #{1 2 3 10 4 5 6} (calls/shown-rows {:row 1 :code "a\nb\nc\nd\ne\nf\ng\nh\ni\nj" :callees [{:at [{:row 5}]}]} [])))
  (is (= {3 [{:start 4 :end 8 :to "x"}]} (calls/links-by-row [{:id "x" :at [{:row 3 :col 5 :endCol 9}]}])))
  (is (= [[:line 0] [:line 1] [:line 2] [:open 3] [:open 4]] (calls/fold-plan 5 1 #{1 2 3} #{})))
  (is (= [[:line 0] [:fold 1 5 2] [:line 5]] (calls/fold-plan 6 1 #{1 6} #{})))
  (is (= [[:line 0] [:open 1] [:open 2] [:open 3] [:open 4] [:line 5]] (calls/fold-plan 6 1 #{1 6} #{2})))
  (is (= [[:line 0] [:line 1]] (calls/fold-plan 2 1 nil #{}))))

(deftest nests-toggle
  (is (= [{:row 3 :to "a"}] (calls/toggle-nest [] 3 "a")))
  (is (= [] (calls/toggle-nest [{:row 3 :to "a"}] 3 "a"))))

(deftest the-map
  (is (= ["src/a" "src/b"] (calls/folder-list [{:folder "src/b" :dist 2} {:folder "src/a" :dist 0} {:folder "src/b" :dist -1}])))
  (is (= [{:k -1 :nodes [{:dist -1 :folder "a" :name "x"}]} {:k 0 :nodes [{:dist 0 :folder "a" :name "a"} {:dist 0 :folder "a" :name "b"}]}]
         (calls/map-columns [{:dist 0 :folder "a" :name "b"} {:dist -1 :folder "a" :name "x"} {:dist 0 :folder "a" :name "a"}])))
  (is (= ["2 up" "start" "1 down"] (map calls/column-label [-2 0 1])))
  (is (= [0 8 3] [(calls/depth-step 0 -1) (calls/depth-step 8 1) (calls/depth-step 2 1)]))
  (is (= ["unfold" "map"] [(calls/view-of "nope") (calls/view-of "map")]))
  (is (= [2 8 3] [(calls/depth nil 2) (calls/depth 12 3) (calls/depth 3.7 1)])))

(deftest arrows-between-boxes
  (let [rects {"a" {:left 0 :right 10 :top 0 :bottom 10 :height 10}
               "b" {:left 20 :right 30 :top 0 :bottom 20 :height 20}}]
    (is (= [{:a "a" :b "b" :d "M10,5 C14,5 14,10 18,10"}] (calls/arrow-paths rects [["a" "b"]])))
    (is (:back (first (calls/arrow-paths rects [["b" "a"]]))))
    (is (empty? (calls/arrow-paths rects [["a" "zzz"]])))))
