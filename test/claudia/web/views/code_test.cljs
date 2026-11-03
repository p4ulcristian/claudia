(ns claudia.web.views.code-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.code :as code]))

(deftest marks-and-names
  (is (= #{3 5 6 7} (code/marked-lines [3 "5-7"])))
  (is (= "var(--muted)" (code/mark-colour {:color "grey"})))
  (is (= "var(--amber)" (code/mark-colour {:color "pink"})))
  (is (= "shop" (code/project-name "/srv/apps/shop/")))
  (is (= "project" (code/project-name ""))))

(deftest row-plan-folds-hidden-runs
  (is (= [[:line 0] [:fold 1 3] [:line 3]] (code/row-plan [true false false true] #{})))
  (is (= [[:line 0] [:line 1] [:line 2] [:line 3]] (code/row-plan [true false false true] #{1}))))

(deftest head-and-copy
  (is (= ":3–9" (code/line-span 3 9)))
  (is (= ":3" (code/line-span 3 3)))
  (is (nil? (code/line-span nil nil)))
  (is (= "a\nc" (code/copy-text [{:text "a" :sign ""} {:text "b" :sign "-"} {:text "c" :sign "+"}]))))
