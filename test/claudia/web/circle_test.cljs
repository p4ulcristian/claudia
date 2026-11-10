(ns claudia.web.circle-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.circle :as circle]))

(deftest a-click-is-not-a-circle
  (is (= 0 (circle/travel [[5 5]])))
  (is (= 5 (circle/travel [[0 0] [3 4] [1 1]])))
  (is (false? (circle/drawn? [[0 0] [3 4]])))
  (is (true? (circle/drawn? [[0 0] [30 40] [0 2]]))))

(deftest the-lines-box
  (is (= {:x 2 :y 1 :w 8 :h 9} (circle/bbox [[4 1] [10 5] [2 10]])))
  (is (= [[11 22] [13 24]] (circle/shift [[1 2] [3 4]] 10 20))))

(deftest clip-pads-then-cuts
  (let [view {:x 0 :y 0 :w 100 :h 100}]
    (is (= {:x 10 :y 10 :w 30 :h 30} (circle/clip {:x 20 :y 20 :w 10 :h 10} 10 view)))
    (testing "cut to the view"
      (is (= {:x 0 :y 85 :w 15 :h 15} (circle/clip {:x 0 :y 95 :w 5 :h 20} 10 view))))
    (testing "out of sight"
      (is (nil? (circle/clip {:x 200 :y 0 :w 5 :h 5} 10 view))))))

(deftest crop-in-frame-pixels
  (is (= {:x 20 :y 30 :w 40 :h 1} (circle/crop {:x 10 :y 15 :w 20 :h 0.1} 2 2)))
  (is (= {:x 15 :y 10 :w 15 :h 10} (circle/crop {:x 10 :y 10 :w 10 :h 10} 1.5 1))))

(deftest path-through-points
  (is (= "M1.0 2.0 L3.5 4.0" (circle/path-d [[1 2] [3.5 4]]))))

(deftest grid-spreads-over-the-box
  (is (= [[5 5] [5 15] [15 5] [15 15]] (vec (circle/grid {:x 0 :y 0 :w 20 :h 20} 2)))))

(deftest label-text-is-short-and-one-line
  (is (= "Go now" (circle/label-text "  Go\n  now ")))
  (is (nil? (circle/label-text "   ")))
  (is (= 300 (count (circle/label-text (apply str (repeat 300 "x"))))))
  (is (nil? (circle/label-text (apply str (repeat 301 "x"))))))

(deftest outermost-keeps-the-card-not-its-parts
  (is (= ["✓ Both windows know Already there" "PR #110"]
         (circle/outermost ["Both windows know" "✓ Both windows know Already there" "Already there" "PR #110" "PR #110"])))
  (is (= ["a paragraph with x in it"] (circle/outermost ["x" "a paragraph with x in it"]))))

(deftest circles-sit-on-their-surface
  (let [items [{:circle {:points [[1 1]]}} {:circle {:points [[2 2]] :surface "chat"}} {:id 3}
               {:circle {:points [[3 3]] :surface "picture"}}]]
    (is (= [[[1 1]] [[3 3]]] (circle/lines-on items "picture")))
    (is (= [[[2 2]]] (circle/lines-on items "chat")))))

(deftest the-note-names-blocks-and-texts
  (is (= "[Circled on the picture: image 2 below]" (circle/note 2 {})))
  (is (= "[Circled in the chat: image 1 below; under the chalk: \"hi\"]" (circle/note 1 {:surface "chat" :texts ["hi"]})))
  (is (= (str "[Circled on the picture: image 1 below; on \"Plan\" (pipeline, id flow), a block (graph, id g)"
              "; under the chalk: \"Go\" · \"Ship it\"]")
         (circle/note 1 {:blocks [{:id "flow" :name "Plan" :kind "form" :form "pipeline"} {:id "g" :kind "graph"}]
                         :texts ["Go" "Ship it"]}))))

(deftest notes-are-numbered-by-image
  (let [items [{:id 1 :images [{:data "p"}]}
               {:id 2 :circle {:texts ["A"]} :images [{:data "c"}]}
               {:id 3 :file {:path "x"}}
               {:id 4 :circle {} :images [{:data "d"}]}]
        out (circle/with-notes items)]
    (is (= [nil "[Circled on the picture: image 2 below; under the chalk: \"A\"]" nil "[Circled on the picture: image 3 below]"]
           (mapv :note out)))
    (is (= [2 4] (mapv :id (circle/circles items))))))

(deftest frame-points-move-to-the-window
  (is (= {:kind "move" :x 110 :y 220} (circle/from-frame {:kind "move" :x 10 :y 20} {:x 100 :y 200}))))
