(ns claudia.web.folders-test
  "Files in the right half (claudia.web.folders): what Select does, where a chat works, and what the
  right half shows (claudia.web.right), on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.folders :as folders]
            [claudia.web.right :as right]))

(deftest select-moves-a-started-chat-or-sets-a-new-chats-pick
  (is (= {:db {:open ["home" "c1"]} :fx [[:dispatch [:chat/folder ["home" "c1"] "/w/app"]]]}
         (folders/use-folder {:open ["home" "c1"]} "/w/app" ["home" "c1"])))
  (is (= {:db {} :fx [[:dispatch [:new/settings {:folder "/w/app"}]]]}
         (folders/use-folder {} "/w/app" nil)))
  (testing "a held first message goes, and the picture comes back"
    (is (= {:db {:drafts {:new "hi"} :right-view :picture :right-saving true} :right/put :picture
            :fx [[:dispatch [:new/settings {:folder "/w/app"}]] [:dispatch [:composer/send "hi"]]]}
           (folders/use-folder {:drafts {:new "hi"} :folders {:send? true}} "/w/app" nil)))))

(deftest where-a-chat-works
  (is (= {:new? true :ck nil :server "s" :folder "/w/a"}
         (folders/chat-place nil "s" {:folder "/w/a"})) "the new chat: its pick on the new chat's server")
  (is (= {:new? false :ck ["h" "c"] :server "h" :folder "/w/b"}
         (folders/chat-place {:ck ["h" "c"] :server "h" :row {:folder "/w/b"}} "s" {})) "a started chat: its own")
  (is (= {:new? false :ck ["h" "c"] :server "h" :folder nil}
         (folders/chat-place {:ck ["h" "c"] :server "h" :row {}} "s" {})) "none: the work folder"))

(deftest the-right-half-shows-the-persons-pick
  (let [fx (right/show-view {:world? true} :files true)]
    (is (= {:right-view :files :right-saving true :screen :picture} (:db fx)) "a phone brings the right half up, the world closes")
    (is (= :files (:right/put fx)) "kept for the person")
    (is (contains? fx :bookmarks/get) "the bookmarks are asked again"))
  (is (= {:db {:right-view :settings :right-saving true} :right/put :settings} (right/show-view {} "settings" false)))
  (is (= :picture (:right-view (:db (right/show-view {} :nonsense false)))) "an unknown view is the Drawing board"))
