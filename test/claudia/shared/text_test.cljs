(ns claudia.shared.text-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.text :as text]))

(deftest cut-keeps-the-first-n-and-marks-the-rest
  (is (= "abc" (text/cut "abc" 3 "…")))
  (is (= "ab\n…" (text/cut "abc" 2 "\n…")))
  (testing "anything is text first"
    (is (= "" (text/cut nil 3 "…")))
    (is (= "12…" (text/cut 1234 2 "…")))))

(deftest fit-stays-within-n-characters
  (is (= "abc" (text/fit "abc" 3)))
  (is (= "ab…" (text/fit "abcd" 3)))
  (is (= 3 (count (text/fit "abcdef" 3)))))

(deftest one-line-folds-whitespace
  (is (= "a b c" (text/one-line "  a\n\tb   c \n"))))
