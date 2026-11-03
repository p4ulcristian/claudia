(ns claudia.web.views.editor-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.editor :as editor]))

(deftest files-and-text
  (is (= [{:id "a"}] (editor/file-list [{:id "a"} {:id 3} {}])))
  (is (= [] (editor/file-list nil)))
  (is (= "typed" (editor/shown {:edit "typed" :code "c"}))))

(deftest sync-plan-keeps-typing
  (testing "Claude's new version replaces the text"
    (is (= {:replace true :change true :text "new"} (editor/sync-plan {:v 1 :text "old"} {:v 2 :code "new"} "typed"))))
  (testing "another page's typing comes in when nothing waits here"
    (is (= {:replace true :change true :text "theirs"} (editor/sync-plan {:v 1 :text "old"} {:v 1 :edit "theirs"} "old"))))
  (testing "typing not saved yet stays"
    (is (= {:replace false :change false :text "old"} (editor/sync-plan {:v 1 :text "old"} {:v 1 :edit "theirs"} "mine"))))
  (testing "nothing changed"
    (is (= {:replace false :change false :text "old"} (editor/sync-plan {:v 1 :text "old"} {:v 1 :code "old"} "old")))))
