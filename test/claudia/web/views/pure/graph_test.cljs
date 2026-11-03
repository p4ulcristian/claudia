(ns claudia.web.views.pure.graph-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.graph :as graph]))

(defn- w8 [s] (* 8 (count s)))

(deftest wrap-keeps-three-lines
  (is (= ["one two" "three"] (graph/wrap "one two three" 8)))
  (is (= ["a" "b" "c"] (graph/wrap "a b c d" 1)))
  (is (= [] (graph/wrap "  " 5))))

(deftest node-size-by-shape
  (testing "a circle is at least 60 across"
    (is (= {:lines ["A"] :notes [] :d 60 :w 60 :h 60} (graph/node-size {:id "A"} w8 w8))))
  (testing "a box"
    (is (= {:lines ["Box"] :notes [] :d 0 :w 96 :h 39} (graph/node-size {:id "b" :label "Box" :shape "box"} w8 w8))))
  (testing "a diamond is wider and taller"
    (is (< 139 (:w (graph/node-size {:id "b" :label "Box" :shape "diamond"} w8 w8)) 140))))

(deftest round-path-bends
  (is (nil? (graph/round-path [{:x 0 :y 0}])))
  (is (= "M0,0 L10,0" (graph/round-path [{:x 0 :y 0} {:x 10 :y 0}])))
  (is (= "M0,0 L5,0 Q10,0 10,5 L10,20" (graph/round-path [{:x 0 :y 0} {:x 10 :y 0} {:x 10 :y 20}]))))

(deftest stack-edges-link-unlinked-members
  (is (= [{:id "~a~b" :sources ["a"] :targets ["b"]}]
         (graph/stack-edges {:nodes [{:id "a" :group "g"} {:id "b" :group "g"} {:id "c" :group "g"}]
                             :edges [{:from "c" :to "b"}]
                             :groups [{:id "g"}]}))))

(deftest elk-graph-for-a-variant
  (let [g {:nodes [{:id "a" :group "g"} {:id "b"}] :edges [{:from "a" :to "b" :label "go"} {:from "a" :to "zz"}]
           :groups [{:id "g" :label "G"}]}
        sizes {"a" {:w 60 :h 60} "b" {:w 96 :h 39}}
        out (graph/elk-graph g :down sizes w8 w8)]
    (is (= "DOWN" (get-in out [:layoutOptions "elk.direction"])))
    (is (= ["g" "b"] (map :id (:children out))))
    (is (= "(60, 42)" (get-in (first (:children out)) [:layoutOptions "elk.nodeSize.minimum"])))
    (is (= [{:id "a->b" :sources ["a"] :targets ["b"] :labels [{:text "go" :width 28 :height 18}]}] (:edges out)))
    (is (= "RIGHT" (get-in (graph/elk-graph g :right sizes w8 w8) [:layoutOptions "elk.direction"])))))

(deftest laid-out-in-root-coordinates
  (let [g {:nodes [{:id "a" :group "g"} {:id "b"}] :edges [{:from "a" :to "b"}] :groups [{:id "g"}]}
        sizes {"a" {:lines ["a"]} "b" {:lines ["b"]}}
        out {:width 300 :height 200
             :children [{:id "g" :x 10 :y 20 :width 100 :height 80 :children [{:id "a" :x 5 :y 6 :width 60 :height 60}]}
                        {:id "b" :x 200 :y 0 :width 96 :height 39}]
             :edges [{:id "a->b" :container "g"
                      :sections [{:startPoint {:x 0 :y 0} :endPoint {:x 10 :y 0}}]}]}
        l (graph/laid-out out :right sizes g)]
    (is (= {:w 300 :h 200 :variant :right} (select-keys l [:w :h :variant])))
    (is (= [{:id "g" :x 10 :y 20 :w 100 :h 80 :group {:id "g"}}] (:groups l)))
    (is (= [[15 26] [200 0]] (map (juxt :x :y) (:nodes l))))
    (is (= ["a"] (:lines (first (:nodes l)))))
    (is (= "M10,20 L20,20" (:d (first (:edges l)))))
    (is (= #{"a" "b" "a->b"} (graph/ids-of l)))))

(deftest choose-keeps-the-last-layout-unless-another-fits-clearly-better
  (let [layouts [{:w 972 :h 100} {:w 472 :h 100} {:w 572 :h 100}]
        variants [:right :down :down-stacked]]
    (is (= 1 (:index (graph/choose layouts variants nil 500 1000))))
    (is (= 2 (:index (graph/choose layouts variants :down-stacked 500 1000))))
    (is (= 1 (:index (graph/choose layouts variants :right 500 1000))))
    (is (= 1 (:scale (graph/choose layouts variants nil 500 1000))))))

(deftest zoom-around-a-point
  (is (= {:k 2 :x -10 :y -10} (graph/zoomed {:k 1 :x 0 :y 0} 10 10 2 0 0)))
  (is (= 4 (:k (graph/zoomed {:k 3 :x 0 :y 0} 0 0 2 0 0))))
  (is (= "grey" (graph/known-color ["red"] "pink" "grey"))))
