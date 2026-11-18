(ns claudia.web.views.prompts-test
  "The prompt picker's pure parts (claudia.web.views.prompts)."
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.prompts :as prompts]))

(deftest picked-name-is-a-known-profile-or-none
  (let [ps [{:name "Reviewer" :text "r"}]]
    (is (= "Reviewer" (prompts/picked-name "Reviewer" ps)))
    (is (nil? (prompts/picked-name "Gone" ps)))
    (is (nil? (prompts/picked-name nil ps)))
    (is (nil? (prompts/picked-name "Reviewer" nil)))))

(deftest first-line-cuts-the-text
  (is (= "Review the diff." (prompts/first-line "  Review the diff.\nNever edit.")))
  (is (= "" (prompts/first-line nil)))
  (is (= 80 (count (prompts/first-line (apply str (repeat 100 "a")))))))
