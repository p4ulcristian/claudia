(ns claudia.web.views.pure.folders-test
  "The Folders screen's data (claudia.web.views.pure.folders) and the project list's bookmarks
  (claudia.web.views.pure.menu/with-bookmarks), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.folders :as pure]
            [claudia.web.views.pure.menu :as menu]))

(deftest paths-up-to-the-root
  (is (= "/home/ana" (pure/parent-of "/home/ana/Work")))
  (is (= "/" (pure/parent-of "/home")))
  (is (nil? (pure/parent-of "/")))
  (is (= ["/" "/home" "/home/ana"] (pure/lineage "/home/ana")))
  (is (= ["/"] (pure/lineage "/")))
  (is (= {"/" true "/w" true} (pure/open-map "/w")))
  (is (= "Work" (pure/base-name "/home/ana/Work")))
  (is (= "/" (pure/base-name "/"))))

(deftest the-tree-from-what-is-loaded
  (let [loaded {"/" [{:name "w" :path "/w" :hidden false} {:name ".cache" :path "/.cache" :hidden true}]
                "/w" []}]
    (is (= [{:id "/" :name "/" :loaded true
             :children [{:id "/w" :name "w" :loaded true :children []}]}]
           (pure/tree-data loaded false)))
    (is (= ["/w" "/.cache"] (map :id (:children (first (pure/tree-data loaded true))))))
    (is (= {:id "/x" :name "x" :loaded false :children []} (pure/tree-node {} false "/x" 0)) "not loaded: openable")))

(deftest the-panel-lines
  (is (= "main · 2 not committed · 1 ahead" (pure/git-text {:branch "main" :dirty 2 :ahead 1 :behind 0})))
  (is (= "detached · clean" (pure/git-text {:branch nil :dirty 0 :ahead 0 :behind 0})))
  (is (nil? (pure/git-text nil)))
  (is (= "1.0 KiB" (pure/size-text {:size 1024})))
  (is (= "measuring…" (pure/size-text {:size nil :measuring true})))
  (is (= "unknown" (pure/size-text {:size nil :measuring false}))))

(deftest unpushed-lines-name-work-only-here
  (is (= ["1 file not committed" "2 commits not pushed" "branches without an upstream: spike"]
         (pure/unpushed-lines {:git {:top "/w/a" :dirty 1 :ahead 2 :noUpstream ["spike"]} :inner []})))
  (is (= ["/srv/b: 3 files not committed"]
         (pure/unpushed-lines {:git nil :inner [{:top "/srv/b" :dirty 3 :ahead 0 :noUpstream []}]})))
  (is (= [] (pure/unpushed-lines {:git {:top "/w" :dirty 0 :ahead 0 :noUpstream []}}))))

(deftest bookmark-rows-mark-missing-folders
  (is (= [{:folder "/w/a" :label "A" :missing false} {:folder "/w/gone" :label "gone" :missing true}]
         (pure/bookmark-rows [{:server "home" :folder "/w/a" :label "A"} {:server "box" :folder "/x"}
                              {:server "home" :folder "/w/gone"}]
                             "home" #{"/w/gone"}))))

(deftest the-project-list-shows-bookmarks-first
  (let [targets [{:key "/w/app" :label "app" :count 3} {:key "/w/iris" :label "iris" :count 1}]
        items [{:server "home" :folder "/w/iris" :label "Iris"} {:server "home" :folder "/srv/data"}
               {:server "box" :folder "/w/app"}]]
    (is (= ["/w/iris" "/srv/data" "/w/app"] (map :key (menu/with-bookmarks targets items "home" ""))))
    (is (= [{:key "/srv/data" :label "data" :bookmark true}] (menu/with-bookmarks [] items "home" "srv")))
    (is (= targets (menu/with-bookmarks targets [] "home" "")))))
