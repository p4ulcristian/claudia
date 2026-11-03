(ns claudia.web.views.git-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.git :as g]))

(deftest patches-split-by-file
  (let [diff "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\ndiff --git a/y b/y\n--- a/y\n+++ /dev/null\n"]
    (is (= ["b/x" "a/y"] (keys (g/split-patches diff))))
    (is (= {} (g/split-patches nil)))))

(deftest commit-files-open-and-shut
  (is (g/small-commit? [1 2] {:additions 10 :deletions 3}))
  (is (not (g/small-commit? (range 9) {:additions 1 :deletions 1})))
  (is (= #{"a"} (g/toggle-shut nil true [{:path "a"}] "a")))
  (is (= #{"b"} (g/toggle-shut nil false [{:path "a"} {:path "b"}] "a"))))

(deftest branches
  (let [bs [{:name "main"} {:name "dev"}]]
    (is (= ["main"] (g/toggle-branch bs nil "dev")))
    (is (nil? (g/toggle-branch bs ["main"] "dev")) "all shown again")
    (is (nil? (g/toggle-branch bs ["main"] "main")) "never none"))
  (is (= [{:name "main" :local true :kind "local" :current true} {:name "origin/main" :local false :kind "remote" :current false}]
         (g/branches-of {:commits [{:refs [{:type "head" :name "main"} {:type "remote" :name "origin/main"} {:type "tag" :name "v1"}]}]})))
  (is (= {"main" "var(--blue)"} (g/branch-colours [{:colorKey 1 :refs [{:type "head" :name "main"} {:type "tag" :name "v1"}]}]))))

(deftest which-commit
  (let [shown {:head "abcdef12" :commits [{:hash "abcdef12" :refs [{:name "main"}]} {:hash "99999999"}]}
        marks [{:commit "main" :note "here"} {:commit "WORKING" :note "now"}]]
    (is (= "abcdef12" (g/open-hash-of "HEAD" shown)))
    (is (= "99999999" (g/open-hash-of "9999" shown)))
    (is (= "WORKING" (g/open-hash-of "WORKING" shown)))
    (is (= "here" (:note (g/mark-of-commit marks "abcdef12" {:hash "abcdef12" :refs [{:name "main"}]}))))
    (is (= "now" (:note (g/mark-of-commit marks "abcdef12" "WORKING"))))))

(deftest graph-rows
  (let [log {:head "b" :dirty {:total 2} :demo? true
             :commits [{:hash "b" :time 1000 :col 0 :parents ["a"]} {:hash "a" :time 990 :col 1}]}
        geo (g/graph-geometry log 0)]
    (is (= 1 (:off geo)))
    (is (= {"b" 1 "a" 2} (:row-of geo)))
    (is (= [0 0 10] (:gap geo)))
    (is (= [0 50 100] (:tops geo)))
    (is (= 150 (:height geo)))
    (is (= 44 (:width geo)))
    (is (= 31 (g/lane-x 1)))))

(deftest heads-and-labels
  (is (= "2 staged · 1 untracked" (g/dirty-summary {:staged 2 :untracked 1})))
  (is (= ["/srv/" "shop"] (g/repo-parts "/srv/shop")))
  (is (= ["~/w/" "shop"] (g/repo-parts "/home/u/w/shop")))
  (is (true? (:demo? (g/demo-log {:commits []})))))
