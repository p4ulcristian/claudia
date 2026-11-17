(ns claudia.shared.narration-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.shared.narration :as narration]))

(deftest a-steps-line
  (is (= "Hello there." (narration/say-of {:say "  Hello there. "})))
  (is (= "" (narration/say-of {:say 3})))
  (is (= 1200 (count (narration/say-of {:say (apply str (repeat 1300 "a"))})))))

(deftest the-storys-lines
  (testing "once each, in order, with the story's voice and language"
    (is (= [{:text "Egy." :instruction "a calm man" :language "hu"} {:text "Kettő." :instruction "a calm man" :language "hu"}]
           (narration/lines {:voice "a calm man" :language "hu"
                             :story [{:say "Egy."} {:show ["a"]} {:say " Kettő. "} {:say "Egy."} "junk"]}))))
  (is (= [{:text "One." :instruction nil :language nil}] (narration/lines {:story [{:say "One."}]})))
  (testing "no narrator, no story: no lines"
    (is (= [] (narration/lines {:narrate false :story [{:say "One."}]})))
    (is (= [] (narration/lines {})))))

(deftest the-storys-language
  (is (= "hu" (narration/language-of {:language " HU "})))
  (is (= "fil" (narration/language-of {:language "fil"})))
  (testing "none, or not a 2- or 3-letter code: nil"
    (is (nil? (narration/language-of {})))
    (is (nil? (narration/language-of {:language 7})))
    (is (nil? (narration/language-of {:language "Hungarian"})))
    (is (nil? (narration/language-of {:language "h"})))))

(deftest clip-lengths-by-line
  (is (= {[nil "One."] 2.5 ["calm" "Two."] 1}
         (narration/seconds-by-line [{:text "One." :instruction nil :seconds 2.5} {:text "Two." :instruction "calm" :seconds 1}]))))
