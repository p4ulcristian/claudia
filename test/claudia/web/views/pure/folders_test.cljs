(ns claudia.web.views.pure.folders-test
  "The Folders modal's data (claudia.web.views.pure.folders), on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.folders :as pure]))

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
    (is (= [{:id "/" :name "/" :loaded true :pinned false
             :children [{:id "/w" :name "w" :loaded true :pinned true :children []}]}]
           (pure/tree-data {:loaded loaded :hidden? false :pinned #{"/w"}})))
    (is (= ["/w" "/.cache"] (map :id (:children (first (pure/tree-data {:loaded loaded :hidden? true :pinned #{}}))))))
    (is (= {:id "/x" :name "x" :loaded false :pinned false :children []} (pure/tree-node {:loaded {}} "/x" 0)) "not loaded: openable")))

(deftest the-filter-field
  (is (pure/name-matches? "Claudia-Folders" "folders"))
  (is (not (pure/name-matches? "apps" "home")))
  (is (= "app" (pure/filter-term " app ")))
  (is (nil? (pure/filter-term "/home/ana")))
  (is (= "/home/ana/Work" (pure/typed-path " /home/ana/Work/ ")))
  (is (nil? (pure/typed-path "Work")))
  (is (nil? (pure/typed-path "/a/../b"))))

(deftest the-panel-lines
  (is (= "main · 2 not committed · 1 ahead" (pure/git-text {:branch "main" :dirty 2 :ahead 1 :behind 0})))
  (is (= "detached · clean" (pure/git-text {:branch nil :dirty 0 :ahead 0 :behind 0})))
  (is (nil? (pure/git-text nil)))
  (is (= "1.0 KiB" (pure/size-text {:size 1024})))
  (is (= "measuring…" (pure/size-text {:size nil :measuring true})))
  (is (= "unknown" (pure/size-text {:size nil :measuring false}))))

(deftest bookmark-rows-mark-missing-folders
  (is (= [{:folder "/w/a" :label "A" :missing false} {:folder "/w/gone" :label "gone" :missing true}]
         (pure/bookmark-rows [{:server "home" :folder "/w/a" :label "A"} {:server "box" :folder "/x"}
                              {:server "home" :folder "/w/gone"}]
                             "home" #{"/w/gone"}))))

(deftest the-contents-pane-and-the-preview
  (testing "dot entries only with show hidden"
    (is (= ["a"] (mapv :name (pure/shown-items [{:name "a"} {:name ".git" :hidden true}] false))))
    (is (= 2 (count (pure/shown-items [{:name "a"} {:name ".git" :hidden true}] true)))))
  (testing "the count line"
    (is (= "Empty" (pure/count-text [] 0)))
    (is (= "1 folder · 2 files" (pure/count-text [{:dir true} {} {}] 0)))
    (is (= "3 files · 9 more not shown" (pure/count-text [{} {} {}] 9))))
  (testing "the README: a Markdown one first, never a folder"
    (is (= "README.md" (:name (pure/readme-of [{:name "readme.txt"} {:name "README.md"} {:name "src" :dir true}]))))
    (is (nil? (pure/readme-of [{:name "README" :dir true}]))))
  (testing "thumbnails only for images up to thumb-max"
    (is (true? (pure/thumb? {:kind "image" :size 1000})))
    (is (false? (pure/thumb? {:kind "image" :size (inc pure/thumb-max)})))
    (is (false? (pure/thumb? {:kind "file" :size 10}))))
  (testing "how each item is previewed"
    (is (= :folder (pure/preview-way {:dir true})))
    (is (= :video (pure/preview-way {:kind "video" :size 1e9})))
    (is (= :markdown (pure/preview-way {:kind "file" :name "Notes.MD" :size 10})))
    (is (= :text (pure/preview-way {:kind "file" :name "core.cljs" :size 10})))
    (is (= :none (pure/preview-way {:kind "file" :name "dump.sql" :size (inc pure/text-max)}))))
  (testing "grid unless list was stored"
    (is (= :list (pure/view-mode "list")))
    (is (= :grid (pure/view-mode nil))))
  (testing "a second click on the same folder within double-click-ms opens it"
    (let [dir {:dir true :path "/w/src"}]
      (is (true? (pure/opens? dir {:path "/w/src" :at 1000} {:path "/w/src" :at 1300}))))
    (is (false? (pure/opens? {:dir true :path "/w/src"} {:path "/w/src" :at 1000} {:path "/w/src" :at (+ 1000 pure/double-click-ms)})))
    (is (false? (pure/opens? {:dir true :path "/w/src"} {:path "/w/test" :at 1000} {:path "/w/src" :at 1100})) "another item first")
    (is (false? (pure/opens? {:dir true :path "/w/src"} nil {:path "/w/src" :at 1100})) "a first click")
    (is (false? (pure/opens? {:path "/w/a.txt"} {:path "/w/a.txt" :at 1000} {:path "/w/a.txt" :at 1100})) "files never open")))
