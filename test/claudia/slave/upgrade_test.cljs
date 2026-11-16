(ns claudia.slave.upgrade-test
  "Saved pictures rewritten into the shape where every block is a form."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.upgrade :as upgrade]))

(deftest blocks-with-a-kind-become-forms
  (is (= {:title "t"
          :blocks [{:id "g" :name "Flow" :width "half" :form "graph"
                    :data {:nodes [{:id "a"} {:id "b"}] :edges [{:from "a" :to "b" :id "a->b"}] :groups []}}
                   {:id "p" :form "html" :data {:html "<p>" :parts {:n "1"} :template "T"}}
                   {:id "s" :form "stats" :data {:tiles []}}]}
         (upgrade/scene {:title "t"
                         :blocks [{:id "g" :kind "graph" :name "Flow" :width "half"
                                   :nodes [{:id "a"} {:id "b"}] :edges [{:from "a" :to "b"}] :groups []}
                                  {:id "p" :kind "html" :html "<p>" :parts {:n "1"} :template "T" :height 300}
                                  {:id "s" :kind "form" :form "stats" :data {:tiles []}}]})))
  (testing "a picture already in the new shape stays as it is"
    (let [s {:title "" :blocks [{:id "s" :form "stats" :data {:tiles []}}]}]
      (is (= s (upgrade/scene s)))
      (is (= s (upgrade/scene (upgrade/scene s)))))))

(deftest pictures-from-before-blocks-become-forms
  (let [old {:title "old" :nodes [{:id "a"}] :edges [] :groups []
             :cards [{:id "1" :kind "text" :title "Why" :text "Because." :color "red"}
                     {:id "2" :kind "list" :items ["one" "two"]}
                     {:id "3" :kind "table" :columns ["A" "B"] :rows [["x|y" "z"]]}
                     {:id "4" :kind "code" :lang "clj" :code "(+ 1 2)"}
                     {:id "5" :kind "page" :title "Mock" :html "<b>hi</b>"}]}]
    (is (= {:title "old"
            :blocks [{:id "graph" :form "graph" :data {:nodes [{:id "a"}] :edges [] :groups []}}
                     {:id "card-1" :form "notes" :data {:title "Why" :text "Because."}}
                     {:id "card-2" :form "notes" :data {:text "- one\n- two"}}
                     {:id "card-3" :form "notes" :data {:text "| A | B |\n| --- | --- |\n| x\\|y | z |"}}
                     {:id "card-4" :form "notes" :data {:text "```clj\n(+ 1 2)\n```"}}
                     {:id "card-5" :name "Mock" :form "html" :data {:html "<b>hi</b>" :parts {}}}]}
           (upgrade/scene old))))
  (testing "a fence longer than the code's own backticks"
    (is (= "````\na ``` b\n````"
           (get-in (upgrade/scene {:cards [{:id "c" :kind "code" :code "a ``` b"}]}) [:blocks 0 :data :text])))))
