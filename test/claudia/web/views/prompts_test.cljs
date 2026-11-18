(ns claudia.web.views.prompts-test
  "The prompt picker's pure part (claudia.web.views.prompts)."
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.prompts :as prompts]))

(deftest picked-value-is-a-known-profile-or-none
  (let [ps [{:name "Reviewer" :text "r"}]]
    (is (= "Reviewer" (prompts/picked-value "Reviewer" ps)))
    (is (= "" (prompts/picked-value "Gone" ps)))
    (is (= "" (prompts/picked-value nil ps)))
    (is (= "" (prompts/picked-value "Reviewer" nil)))))
