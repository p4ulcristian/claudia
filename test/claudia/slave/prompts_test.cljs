(ns claudia.slave.prompts-test
  "Prompt profiles (claudia.slave.prompts), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.prompts :as prompts]))

(deftest clean-name-trims-and-refuses-blank
  (is (= "Reviewer" (prompts/clean-name "  Reviewer ")))
  (is (nil? (prompts/clean-name "   ")))
  (is (nil? (prompts/clean-name 7)))
  (is (= prompts/max-name (count (prompts/clean-name (apply str (repeat 100 "a")))))))

(deftest valid-profiles-keeps-the-first-of-a-name-sorted
  (is (= [{:name "alpha" :text ""} {:name "Beta" :text "b"}]
         (prompts/valid-profiles [{:name "Beta" :text "b"} {:name "alpha" :text 3}
                                  {:name "Beta" :text "again"} {:name " "} "junk"])))
  (is (= [] (prompts/valid-profiles nil))))

(deftest with-and-without-profile
  (let [ps [{:name "A" :text "a"}]]
    (is (= [{:name "A" :text "new"}] (prompts/with-profile ps "A" "new")))
    (is (= [{:name "A" :text "a"} {:name "B" :text "b"}] (prompts/with-profile ps "B" "b")))
    (is (= [] (prompts/without-profile ps "A")))
    (is (= {:name "A" :text "a"} (prompts/find-profile ps "A")))
    (is (nil? (prompts/find-profile ps "Z")))))

(deftest prompt-part-heads-the-text
  (is (= "# Prompt profile: Reviewer\n\nReview only." (prompts/prompt-part {:name "Reviewer" :text "  Review only.\n"})))
  (is (nil? (prompts/prompt-part {:name "Empty" :text "  "})))
  (is (nil? (prompts/prompt-part nil))))

(deftest route-names-the-call
  (is (= {:op :list} (prompts/route "GET" "/api/prompts")))
  (is (= {:op :save :name "My profile"} (prompts/route "PUT" "/api/prompts/My%20profile")))
  (is (= {:op :delete :name "A"} (prompts/route "DELETE" "/api/prompts/A")))
  (is (nil? (prompts/route "PUT" "/api/prompts/%20")))
  (is (nil? (prompts/route "POST" "/api/prompts")))
  (is (nil? (prompts/route "GET" "/api/promptsx"))))
