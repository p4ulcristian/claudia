(ns claudia.shared.narration-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.narration :as narration]))

(deftest a-steps-line
  (is (= "Hello there." (narration/say-of {:say "  Hello there. "})))
  (is (= "" (narration/say-of {:say 3})))
  (is (= 1200 (count (narration/say-of {:say (apply str (repeat 1300 "a"))})))))

(deftest the-storys-lines
  (testing "once each, in order, with the story's voice"
    (is (= [{:text "One." :instruction "a calm man"} {:text "Two." :instruction "a calm man"}]
           (narration/lines {:voice "a calm man"
                             :story [{:say "One."} {:show ["a"]} {:say " Two. "} {:say "One."} "junk"]}))))
  (is (= [{:text "One." :instruction nil}] (narration/lines {:story [{:say "One."}]})))
  (testing "no narrator, no story: no lines"
    (is (= [] (narration/lines {:narrate false :story [{:say "One."}]})))
    (is (= [] (narration/lines {})))))

(deftest clip-lengths-by-line
  (is (= {[nil "One."] 2.5 ["calm" "Two."] 1}
         (narration/seconds-by-line [{:text "One." :instruction nil :seconds 2.5} {:text "Two." :instruction "calm" :seconds 1}]))))
