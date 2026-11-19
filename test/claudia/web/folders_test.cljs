(ns claudia.web.folders-test
  "Files in the right half (claudia.web.folders): what Select does, where a chat works, and what the
  right half shows, on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.folders :as folders]))

(deftest select-moves-a-started-chat-or-sets-a-new-chats-pick
  (is (= {:db {:open ["home" "c1"]} :fx [[:dispatch [:chat/folder ["home" "c1"] "/w/app"]]]}
         (folders/use-folder {:open ["home" "c1"]} "/w/app" ["home" "c1"])))
  (is (= {:db {} :fx [[:dispatch [:new/settings {:folder "/w/app"}]]]}
         (folders/use-folder {} "/w/app" nil)))
  (testing "a held first message goes, and the picture comes back"
    (is (= {:db {:drafts {:new "hi"} :right-view :picture}
            :fx [[:dispatch [:new/settings {:folder "/w/app"}]] [:dispatch [:composer/send "hi"]]]}
           (folders/use-folder {:drafts {:new "hi"} :folders {:send? true}} "/w/app" nil)))))

(deftest where-a-chat-works
  (is (= {:new? true :ck nil :server "s" :folder "/w/a"}
         (folders/chat-place nil "s" {:folder "/w/a"})) "the new chat: its pick on the new chat's server")
  (is (= {:new? false :ck ["h" "c"] :server "h" :folder "/w/b"}
         (folders/chat-place {:ck ["h" "c"] :server "h" :row {:folder "/w/b"}} "s" {})) "a started chat: its own")
  (is (= {:new? false :ck ["h" "c"] :server "h" :folder nil}
         (folders/chat-place {:ck ["h" "c"] :server "h" :row {}} "s" {})) "none: the work folder"))

(deftest the-right-half-shows-the-picture-or-files
  (is (= :files (folders/right-view "files")))
  (is (= :picture (folders/right-view nil)))
  (let [fx (folders/show-view {:world? true} :files true)]
    (is (= {:right-view :files :screen :picture} (:db fx)) "a phone brings the right half up")
    (is (contains? fx :bookmarks/get) "the bookmarks are asked again"))
  (is (= {:db {:right-view :picture} :folders/store-view :picture} (folders/show-view {} :picture false))))
