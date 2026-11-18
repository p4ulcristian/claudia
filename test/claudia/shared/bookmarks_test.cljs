(ns claudia.shared.bookmarks-test
  "A person's bookmarked folders (claudia.shared.bookmarks), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.bookmarks :as bm]))

(deftest clean-folder-keeps-absolute-paths-only
  (is (= "/home/ana/Work" (bm/clean-folder "/home/ana/Work/")))
  (is (= "/a/b" (bm/clean-folder "//a//b")))
  (is (= "/" (bm/clean-folder "/")))
  (is (nil? (bm/clean-folder "Work/app")))
  (is (nil? (bm/clean-folder "/a/../etc")))
  (is (nil? (bm/clean-folder "/a/./b")))
  (is (nil? (bm/clean-folder 7)))
  (is (nil? (bm/clean-folder (str "/" (apply str (repeat 5000 "a")))))))

(deftest clean-items-dedupes-and-caps
  (is (= [{:server "home" :folder "/w/app" :label "App"} {:server "box" :folder "/w/app"}]
         (bm/clean-items [{:server "home" :folder "/w/app/" :label "  App "}
                          {:server "home" :folder "/w/app" :label "again"}
                          {:server "box" :folder "/w/app" :label "  "}
                          {:server "Bad Name" :folder "/x"}
                          {:server "home" :folder "rel"}
                          "junk"])))
  (is (= [] (bm/clean-items nil)))
  (is (= bm/max-items (count (bm/clean-items (for [i (range 300)] {:server "home" :folder (str "/f" i)})))))
  (is (= bm/max-label (count (:label (bm/clean-bookmark {:server "s" :folder "/x" :label (apply str (repeat 200 "l"))}))))))

(deftest toggled-adds-and-removes
  (let [items [{:server "home" :folder "/a"}]]
    (is (= [{:server "home" :folder "/a"} {:server "box" :folder "/a"}] (bm/toggled items "box" "/a")))
    (is (= [] (bm/toggled items "home" "/a")))
    (is (bm/bookmarked? items "home" "/a"))
    (is (not (bm/bookmarked? items "box" "/a")))
    (is (= items (bm/on-server (conj items {:server "box" :folder "/b"}) "home")))))

(deftest label-of-falls-back-to-the-folder-name
  (is (= "App" (bm/label-of {:folder "/w/app" :label "App"})))
  (is (= "app" (bm/label-of {:folder "/w/app"})))
  (is (= "/" (bm/label-of {:folder "/"}))))
