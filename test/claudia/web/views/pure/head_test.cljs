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

(deftest project-rows-are-the-servers-bookmarks-the-chats-ticked
  (is (= [{:folder "/home/ann/Work/app" :title "App" :line "~/Work/app" :on? false}
          {:folder "/home/ann/notes" :title "notes" :line "~/notes" :on? true}]
         (head/project-rows bookmarks "home" "/home/ann/notes")))
  (is (= [] (head/project-rows bookmarks "mac" "/x"))))

(deftest the-project-pill-shows-the-bookmark-else-the-path
  (is (= "App" (head/project-value bookmarks "home" "/home/ann/Work/app")))
  (is (= "~/Work/other" (head/project-value bookmarks "home" "/home/ann/Work/other")))
  (is (= "/srv/site" (head/project-value bookmarks "home" "/srv/site")) "another server's bookmark is not this one's")
  (is (nil? (head/project-value bookmarks "home" nil))))

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
