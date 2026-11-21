(ns claudia.hub.listen-prefs-test
  "A person's dictation choices cleaned (claudia.hub.listen-prefs/clean), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.listen-prefs :as prefs]))

(deftest prefs-are-cleaned
  (is (= prefs/blank (prefs/clean nil)) "nothing stored: nothing declined, offers on")
  (is (= {:declined ["Otto" "GitHub"] :learned [] :offers false}
         (prefs/clean {:declined [" Otto " "otto" 7 "GitHub" ""] :learned "x" :offers false}))
      "words trimmed, repeats and non-text dropped; offers off only when false")
  (is (true? (:offers (prefs/clean {:offers "no"}))) "anything but false keeps offers on")
  (is (= [] (:declined (prefs/clean {:declined [(apply str (repeat 61 "a"))]}))) "a list with a word too long: kept empty"))
