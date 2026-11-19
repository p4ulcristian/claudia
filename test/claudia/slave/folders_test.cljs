(ns claudia.slave.folders-test
  "The folder browser's rules (claudia.slave.folders.rules), its routes and who may call them, on
  plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.folders :as folders]
            [claudia.slave.folders.rules :as rules]
            [claudia.slave.front :as front]))

(deftest error-reply-names-the-os-error
  (is (= 403 (:status (rules/error-reply "EACCES" "x" "/a"))))
  (is (= 403 (:status (rules/error-reply "EPERM" "x" "/a"))))
  (is (= {:status 404 :error "No such folder: /a"} (rules/error-reply "ENOENT" "x" "/a")))
  (is (= 400 (:status (rules/error-reply "ENOTDIR" "x" "/a"))))
  (is (= 400 (:status (rules/error-reply "EINVAL" "x" "a"))))
  (is (= 409 (:status (rules/error-reply "EBUSY" "busy" "/a"))))
  (is (= {:status 500 :error "boom: /a"} (rules/error-reply "EIO" "boom" "/a"))))

(deftest listing-keeps-folders-sorted-by-name
  (is (= [{:name ".git" :path "/w/.git" :dir true :mtime 1 :hidden true}
          {:name "apps" :path "/w/apps" :dir true :mtime 2 :hidden false}
          {:name "Zeta" :path "/w/Zeta" :dir true :mtime nil :hidden false}]
         (rules/listing "/w" [{:name "Zeta" :dir? true :mtime nil} {:name "notes.txt" :dir? false}
                              {:name "apps" :dir? true :mtime 2} {:name ".git" :dir? true :mtime 1}])))
  (is (= "/x" (:path (rules/entry-row "/" {:name "x" :mtime 0})))))

(deftest a-symlink-to-a-folder-is-listed-and-named
  (is (= [{:name "wt" :path "/h/wt" :dir true :mtime 3 :hidden false :link "/srv/wt"}]
         (rules/listing "/h" [{:name "wt" :dir? true :mtime 3 :link "/srv/wt"}
                              {:name "old" :dir? false :link ""}]))
      "kept at the link's own path; a link to no folder stays out of the tree")
  (is (= {:name "old" :path "/h/old" :dir false :mtime nil :size nil :hidden false :kind "file" :link ""}
         (rules/content-row "/h" {:name "old" :dir? false :mtime nil :size nil :link ""}))
      "a broken link in the contents pane says so")
  (is (not (contains? (rules/content-row "/h" {:name "a.txt" :dir? false :mtime 1 :size 2}) :link))))

(deftest the-trash-expires-after-a-week
  (let [day (* 24 60 60 1000)
        items [{:id "a" :at 0 :size 10} {:id "b" :at (* 6 day) :size nil} {:id "c" :at day :size 5}]]
    (is (= ["a"] (map :id (rules/expired items (* 7 day)))))
    (is (= ["a" "c"] (map :id (rules/expired items (* 8 day)))))
    (is (= [] (rules/expired items (dec (* 7 day)))))
    (is (= {:dir "/d/trash/i" :moved "/d/trash/i/app"} (rules/trash-place "/d" "i" "app")))))

(deftest folder-calls-route-and-are-the-owners-only
  (is (= :list (folders/route "GET" "/api/folders")))
  (testing "Folders never delete (README decision 69): the old trash and delete calls are gone"
    (doseq [u ["/api/folders/trash" "/api/folders/delete" "/api/folders/restore" "/api/folders/purge"]]
      (is (= :not-found (folders/route "POST" u)) u))
    (is (= :not-found (folders/route "GET" "/api/folders/trashed"))))
  (is (nil? (folders/route "GET" "/api/foldersx")))
  (is (folders/own? {:via "own"}))
  (is (not (folders/own? {:via "write-tick"})))
  (is (not (folders/own? {})))
  (is (front/folders-refused? "/api/folders" {:viewing "ana" :read-only true}))
  (is (front/folders-refused? "/api/folders/trash" {:viewing "ana" :read-only false}))
  (is (not (front/folders-refused? "/api/folders/trash" {:viewing nil :read-only false})))
  (is (not (front/folders-refused? "/api/chats" {:viewing "ana" :read-only true}))))

(deftest a-files-kind-and-preview-type
  (is (= "image" (rules/kind-of "Logo.PNG")))
  (is (= "video" (rules/kind-of "clip.webm")))
  (is (= "audio" (rules/kind-of "a.mp3")))
  (is (= "pdf" (rules/kind-of "Terms.pdf")))
  (is (= "file" (rules/kind-of "core.cljs")))
  (is (= "file" (rules/kind-of ".png")) "a leading dot is no extension")
  (is (= "image/svg+xml" (rules/preview-type "icon.svg")))
  (is (nil? (rules/preview-type "page.html")) "HTML is never sent as itself"))

(deftest a-folders-contents
  (testing "folders first, then files, each by name case folded"
    (is (= ["b" "Z" "a.txt" "C.md"]
           (mapv :name (rules/ordered [{:name "C.md"} {:name "Z" :dir? true} {:name "a.txt"} {:name "b" :dir? true}])))))
  (testing "rows as the page gets them; the count of what was left out"
    (is (= {:path "/w"
            :entries [{:name "src" :path "/w/src" :dir true :mtime 5 :size nil :hidden false :kind "folder"}
                      {:name ".env" :path "/w/.env" :dir false :mtime 6 :size 10 :hidden true :kind "file"}]
            :more 3}
           (rules/contents "/w" [{:name ".env" :dir? false :mtime 6 :size 10} {:name "src" :dir? true :mtime 5 :size 4096}] 5))))
  (testing "at most max-contents entries are stat'ed"
    (is (= rules/max-contents (count (rules/first-entries (map (fn [i] {:name (str i)}) (range (+ 10 rules/max-contents))))))))
  (testing "the two new calls are routes"
    (is (= :contents (folders/route "GET" "/api/folders/contents")))
    (is (= :file (folders/route "GET" "/api/folders/file")))))
