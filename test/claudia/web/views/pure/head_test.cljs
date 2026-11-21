(ns claudia.web.views.pure.head-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.head :as head]))

(def ^:private bookmarks
  [{:server "home" :folder "/home/ann/Work/app" :label "App"}
   {:server "work" :folder "/srv/site" :label "Site"}
   {:server "home" :folder "/home/ann/notes"}])

(deftest a-project-is-named-by-its-label-else-its-last-folder
  (is (= "App" (head/project-name {:folder "/x/y" :label "App"})))
  (is (= "notes" (head/project-name {:folder "/home/ann/notes"})))
  (is (= "/" (head/project-name {:folder "/"}))))

(def ^:private rows
  [{:server "home" :folder "/home/ann/shop" :at 30}
   {:server "home" :folder "/home/ann/notes" :at 50}
   {:server "home" :folder "/home/ann/shop" :at 10}
   {:server "home" :folder "" :at 60}
   {:server "work" :folder "/srv/api" :at 90}
   {:server "home" :folder "/home/ann/blog" :at 20}])

(def ^:private projects {["home" "/home/ann/shop"] {:name "Shop"} ["home" "/home/ann/blog"] {:name nil}})

(deftest chat-folders-are-the-latest-used-first-each-once-on-the-server
  (is (= ["/home/ann/notes" "/home/ann/shop" "/home/ann/blog"] (head/chat-folders rows "home" #{})))
  (is (= ["/home/ann/shop" "/home/ann/blog"] (head/chat-folders rows "home" #{"/home/ann/notes"})))
  (is (= head/used-max (count (head/chat-folders (map (fn [i] {:server "s" :folder (str "/f" i) :at i}) (range 30)) "s" #{})))))

(deftest project-rows-are-the-bookmarks-then-the-chats-folders-then-the-own
  (is (= [{:folder "/home/ann/Work/app" :title "App" :line "~/Work/app" :on? false :pinned? true}
          {:folder "/home/ann/notes" :title "notes" :line "~/notes" :on? false :pinned? true}
          {:folder "/home/ann/shop" :title "Shop" :line "~/shop" :on? true :pinned? false}
          {:folder "/home/ann/blog" :title "blog" :line "~/blog" :on? false :pinned? false}]
         (head/project-rows bookmarks rows projects "home" "/home/ann/shop")))
  (is (= ["/home/ann/Work/app" "/home/ann/notes" "/home/ann/shop" "/home/ann/blog" "/tmp/x"]
         (mapv :folder (head/project-rows bookmarks rows projects "home" "/tmp/x")))
      "the chat's own folder comes last when nothing else has it")
  (is (= [] (head/project-rows bookmarks rows projects "mac" nil))))

(deftest the-pill-names-the-bookmark-else-the-found-project-else-the-last-folder
  (is (= "App" (head/project-value bookmarks projects "home" "/home/ann/Work/app")))
  (is (= "Shop" (head/project-value bookmarks projects "home" "/home/ann/shop")))
  (is (= "other" (head/project-value bookmarks projects "home" "/home/ann/Work/other")))
  (is (= "site" (head/project-value bookmarks projects "home" "/srv/site")) "another server's bookmark is not this one's")
  (is (nil? (head/project-value bookmarks projects "home" nil))))

(deftest server-rows-tick-the-chats-and-dim-the-closed
  (is (= [{:name "home" :title "Home" :on? true :off? false}
          {:name "mac" :title "mac" :on? false :off? true}]
         (head/server-rows [{:name "home" :label "Home"} {:name "mac"}] "home" {"mac" {:status :closed} "home" {:status :open}}))))

(deftest a-server-pick-starts-a-new-chat-there-or-moves-a-started-one
  (is (= [:new/server "mac"] (head/server-pick {:new? true :server "home"} "mac")))
  (is (= [:move/ask ["home" "c1"] "mac"] (head/server-pick {:new? false :ck ["home" "c1"] :server "home"} "mac")))
  (is (nil? (head/server-pick {:new? false :ck ["home" "c1"] :server "home"} "home"))))

(deftest the-server-pill-is-passive-when-nothing-can-change
  (is (head/server-passive? {:new? true} [{:name "home"}] false) "one server")
  (is (not (head/server-passive? {:new? true} [{:name "home"} {:name "mac"}] false)))
  (is (head/server-passive? {:new? false} [{:name "home"} {:name "mac"}] false) "may not move")
  (is (not (head/server-passive? {:new? false} [{:name "home"} {:name "mac"}] true)))
  (is (head/server-passive? {:new? false :ro true} [{:name "home"} {:name "mac"}] true)))
