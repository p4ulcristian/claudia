(ns claudia.shared.views-test
  "The pile of sheets on the Drawing board (claudia.shared.views, README decision 91), on plain data."
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.views :as views]))

(deftest a-pile-is-cleaned-from-what-was-stored-or-sent
  (is (= [:files :search] (views/clean-pile ["files" "search"])))
  (is (= [] (views/clean-pile ["picture" "nonsense" nil])) "the board is never a sheet, unknown names go")
  (is (= [:search :files] (views/clean-pile [:files :search "files"])) "each once, where it lies highest")
  (is (= [] (views/clean-pile nil)))
  (is (= [] (views/clean-pile "files")) "only a list is a pile"))

(deftest the-top-is-the-last-sheet-or-the-board
  (is (= :picture (views/top [])))
  (is (= :settings (views/top [:files :settings]))))

(deftest opening-lays-a-sheet-on-top
  (is (= [:files] (views/open [] :files)))
  (is (= [:files :search] (views/open [:files] "search")))
  (is (= [:search :files] (views/open [:files :search] :files)) "one already there comes to the top")
  (is (= [] (views/open [:files :search] :picture)) "the board takes every sheet off"))

(deftest closing-takes-a-sheet-off
  (is (= [:files] (views/close [:files :search] :search)))
  (is (= [:search] (views/close [:files :search] :files)) "one under the top")
  (is (= [:files] (views/close [:files] :prompts)) "one not there: nothing changes"))

(deftest toggling-closes-the-top-sheet-else-opens-it
  (is (= [:files] (views/toggle [:files :prompts] :prompts)))
  (is (= [:prompts :files] (views/toggle [:files :prompts] :files)) "under the top: brought up")
  (is (= [:files :prompts] (views/toggle [:files] :prompts)))
  (is (= [] (views/toggle [] :picture)))
  (is (= [] (views/toggle [:files] :picture))))

(deftest a-read-only-view-shows-no-file-browser
  (is (= [:search] (views/shown [:files :search] true)))
  (is (= [:files :search] (views/shown [:files :search] false))))

(deftest the-wire-carries-the-pile-and-its-top
  (is (= {:views ["files" "search"] :view "search"} (views/to-wire [:files :search])))
  (is (= {:views [] :view "picture"} (views/to-wire [])))
  (testing "from a page"
    (is (= [:files :search] (views/from-wire {:views ["files" "search"] :view "search"})))
    (is (= [] (views/from-wire {:views [] :view "files"})) "views wins over view")
    (is (= [:settings] (views/from-wire {:view "settings"})) "an older page: a pile of its one view")
    (is (= [] (views/from-wire {:view "picture"})))
    (is (= [] (views/from-wire {})))))

(deftest the-hub-keeps-names-space-separated
  (is (= "files search" (views/to-stored [:files :search])))
  (is (= "" (views/to-stored [])))
  (is (= [:files :search] (views/from-stored "files search")))
  (testing "a pick kept before the pile"
    (is (= [:files] (views/from-stored "files")))
    (is (= [] (views/from-stored "picture")))
    (is (= [] (views/from-stored nil)))
    (is (= [] (views/from-stored "")))))
