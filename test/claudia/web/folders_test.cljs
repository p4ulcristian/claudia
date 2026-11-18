(ns claudia.web.folders-test
  "What the Folders modal's Select does (claudia.web.folders/use-folder), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.folders :as folders]))

(deftest select-moves-a-started-chat-or-sets-a-new-chats-pick
  (is (= {:db {:open ["home" "c1"]} :fx [[:dispatch [:chat/folder ["home" "c1"] "/w/app"]]]}
         (folders/use-folder {:open ["home" "c1"] :folders {:server "home" :chat ["home" "c1"]}} "/w/app")))
  (is (= {:db {} :fx [[:dispatch [:new/settings {:folder "/w/app"}]]]}
         (folders/use-folder {:folders {:server "home" :chat nil}} "/w/app"))))
