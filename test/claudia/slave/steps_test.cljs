(ns claudia.slave.steps-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [claudia.shared.protocol :as p]
            [claudia.slave.steps :as steps]))

(def listing [{:turn "a" :title "Plan" :at 0} {:turn "b" :title "" :at 1700000000000} {:turn "c" :title "Live" :at 1700000060000}])

(deftest a-step-is-its-place-in-the-list
  (is (= 1 (steps/number-of listing "a")))
  (is (= 3 (steps/number-of listing "c")))
  (is (nil? (steps/number-of listing "x")))
  (is (= "b" (steps/turn-of listing 2)))
  (is (nil? (steps/turn-of listing 0)))
  (is (nil? (steps/turn-of listing 4)))
  (is (nil? (steps/turn-of listing "2")) "a step number is a number"))

(deftest claude-hears-of-an-older-step-only
  (is (= "The user is looking at step 2 of 3 (step 3 is live) while writing this." (steps/viewing-note listing "b")))
  (is (nil? (steps/viewing-note listing "c")) "the live step says nothing")
  (is (nil? (steps/viewing-note listing nil)))
  (is (nil? (steps/viewing-note listing "gone")) "a step no longer there says nothing"))

(deftest from-names-the-step-to-copy
  (is (nil? (steps/from-turn listing {})))
  (is (= {:turn "a"} (steps/from-turn listing {:from 1})))
  (is (str/includes? (:error (steps/from-turn listing {:from 1 :clear true})) "not both"))
  (is (= "There is no step 9: this chat has steps 1 to 3." (:error (steps/from-turn listing {:from 9}))))
  (is (= "There is no step 1: this chat has no steps yet." (:error (steps/from-turn [] {:from 1}))))
  (is (= "A step number is a whole number from 1." (steps/step-error listing 0)))
  (is (nil? (steps/step-error listing 3))))

(deftest changes-count-blocks-drawn-and-removed
  (let [base {:blocks [{:id "x"} {:id "y"}]}]
    (is (= "2 blocks changed" (steps/changes base {:blocks [{:id "x"} {:id "z"}]} ["x" "x:n1" "z"])))
    (is (= "1 block changed, 1 removed" (steps/changes base {:blocks [{:id "x"}] :removeBlocks ["y" "q"]} ["x"])))
    (is (= "1 block changed" (steps/changes base {:blocks [{:id "x"} {:id "h"}]} ["x"])) "a refused block did not change")
    (is (nil? (steps/changes base {:title "t"} [])))))

(deftest the-result-names-the-step
  (is (= "Step 8 · modified from 7 · 2 blocks changed" (steps/head 8 false {} "2 blocks changed")))
  (is (= "Step 8 · fresh (clear)" (steps/head 8 false {:clear true} nil)))
  (is (= "Step 8 · copied from 3 · 1 block changed" (steps/head 8 false {:from 3} "1 block changed")))
  (is (= "Step 1 · the first step" (steps/head 1 false {} nil)))
  (is (= "Updated step 8 (this turn's step) · modified from 7" (steps/head 8 true {} nil))))

(deftest the-list-names-each-step
  (is (= "No steps yet: nothing has been drawn in this chat." (steps/list-text [])))
  (is (= (str "3 steps, oldest first:\n1. Plan · unknown time\n2. (no title) · 2023-11-14T22:13Z\n"
              "3. Live · 2023-11-14T22:14Z · live")
         (steps/list-text listing)))
  (testing "only the newest 60"
    (let [many (vec (for [i (range 70)] {:turn (str i) :title (str "t" i) :at 0}))
          lines (str/split-lines (steps/list-text many))]
      (is (= "70 steps, oldest first (the newest 60 shown):" (first lines)))
      (is (str/starts-with? (second lines) "11. t10"))
      (is (= 61 (count lines))))))

(deftest a-step-reads-as-json
  (let [scene {:title "T" :blocks [{:id "small" :form "notes" :data {:text "hi"}}
                                   {:id "big" :name "Big" :form "notes" :data {:text (apply str (repeat 200 "x"))}}]}]
    (is (= {:title "T" :blocks (:blocks scene)}
           (p/read-json (second (str/split (steps/scene-text 2 3 scene nil 30000) #"\n" 2)))))
    (is (str/starts-with? (steps/scene-text 2 3 scene nil 30000) "Step 2 of 3 (read only; step 3 is live):"))
    (is (str/starts-with? (steps/scene-text 3 3 scene nil 30000) "Step 3 of 3 (live):"))
    (testing "a block over the limit comes without its data"
      (let [out (p/read-json (second (str/split (steps/scene-text 2 3 scene nil 100) #"\n" 2)))]
        (is (= {:text "hi"} (:data (first (:blocks out)))))
        (is (= {:id "big" :name "Big" :form "notes"} (dissoc (second (:blocks out)) :data)))
        (is (str/includes? (:data (second (:blocks out))) "steps { number: 2, block: \"big\" }"))))
    (testing "one block, whole"
      (is (str/includes? (steps/scene-text 2 3 scene "big" 100) (apply str (repeat 200 "x"))))
      (is (= "Step 2 has no block \"q\"." (steps/scene-text 2 3 scene "q" 100))))))
