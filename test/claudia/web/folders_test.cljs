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
  (testing "a held first message goes, and the File Browser's sheet closes"
    (is (= {:db {:drafts {:new "hi"} :right-sheets [:search] :right-saving true} :right/put [:search]
            :fx [[:dispatch [:new/settings {:folder "/w/app"}]] [:dispatch [:composer/send "hi"]]]}
           (folders/use-folder {:drafts {:new "hi"} :folders {:send? true} :right-sheets [:search :files]} "/w/app" nil)))))

(deftest where-a-chat-works
  (is (= {:new? true :ck nil :server "s" :folder "/w/a"}
         (folders/chat-place nil "s" {:folder "/w/a"})) "the new chat: its pick on the new chat's server")
  (is (= {:new? false :ck ["h" "c"] :server "h" :folder "/w/b"}
         (folders/chat-place {:ck ["h" "c"] :server "h" :row {:folder "/w/b"}} "s" {})) "a started chat: its own")
  (is (= {:new? false :ck ["h" "c"] :server "h" :folder nil}
         (folders/chat-place {:ck ["h" "c"] :server "h" :row {}} "s" {})) "none: the work folder"))

(deftest the-right-half-shows-the-persons-pile
  (let [fx (right/show-view {:world? true} :files true)]
    (is (= {:right-sheets [:files] :right-saving true :screen :picture} (:db fx)) "a phone brings the right half up, the world closes")
    (is (= [:files] (:right/put fx)) "kept for the person")
    (is (contains? fx :bookmarks/get) "the bookmarks are asked again"))
  (is (= {:db {:right-sheets [:files :settings] :right-saving true} :right/put [:files :settings]}
         (right/show-view {:right-sheets [:files]} "settings" false)) "a sheet opens on top")
  (is (= [] (:right-sheets (:db (right/show-view {:right-sheets [:files]} :nonsense false)))) "an unknown view is the Drawing board")
  (testing "the Drawing board takes every sheet off and leaves the world and the screen"
    (is (= {:db {:right-sheets [] :right-saving true :world? true} :right/put []}
           (right/show-view {:right-sheets [:files :search] :world? true} :picture true)))))

(deftest toggling-and-closing-sheets
  (is (= {:db {:right-sheets [:files] :right-saving true :world? true} :right/put [:files]}
         (right/toggle-view {:right-sheets [:files :prompts] :world? true} :prompts true)) "the top sheet closes: no world, no screen change")
  (let [fx (right/toggle-view {:right-sheets [:prompts :files]} :prompts true)]
    (is (= [:files :prompts] (:right-sheets (:db fx))) "one under the top comes up")
    (is (= :picture (:screen (:db fx))) "and brings a phone's right half up"))
  (is (contains? (right/toggle-view {:right-sheets [:files :prompts]} :files false) :bookmarks/get) "Files brought up asks the bookmarks again")
  (is (= {:db {:right-sheets [:search] :right-saving true} :right/put [:search]}
         (right/close-view {:right-sheets [:files :search]} :files))))
