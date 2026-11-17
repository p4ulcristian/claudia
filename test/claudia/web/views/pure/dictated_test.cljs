(ns claudia.web.views.pure.dictated-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.dictated :as d]))

(def piece {:text "Ask mission about the rims." :raw "ask mission about the rims" :shown :text})

(deftest added-keeps-pieces-with-text
  (is (= [{:text "hi" :raw "hi um" :shown :text}] (d/added nil {:text " hi " :raw "hi um"})))
  (is (= [] (d/added [] {:text "  " :raw "um"})))
  (is (= {:text "x" :raw "x" :shown :text} (first (d/added nil {:text "x"}))) "no raw: the text")
  (is (= 8 (count (reduce d/added [] (map #(hash-map :text (str %)) (range 12)))))))

(deftest swapping-raw-and-clean
  (let [draft "First. Ask mission about the rims."]
    (is (= piece (d/swappable [piece] draft)))
    (is (nil? (d/swappable [piece] "something else")) "edited away: no chip")
    (is (nil? (d/swappable [(assoc piece :raw (:text piece))] draft)) "the same both ways: no chip")
    (let [[draft' [p']] (d/swapped draft [piece] piece)]
      (is (= "First. ask mission about the rims" draft'))
      (is (= :raw (:shown p')))
      (is (= [draft [piece]] (d/swapped draft' [p'] p')) "and back"))))

(deftest a-corrected-name-is-found
  (is (= "Dorka" (d/corrected-word piece "Ask Dorka about the rims.")))
  (is (= "Dorka" (d/corrected-word piece "Hi! Ask Dorka about the rims, please.")) "inside a longer message")
  (is (= "GitHub" (d/corrected-word {:text "push it to git hub now" :shown :text} "push it to GitHub hub now")))
  (testing "not offered"
    (is (nil? (d/corrected-word piece "Ask mission about the rims.")) "unchanged")
    (is (nil? (d/corrected-word piece "Ask Mission about the rims.")) "only the case changed")
    (is (nil? (d/corrected-word piece "Ask nobody about the rims.")) "not a name")
    (is (nil? (d/corrected-word piece "Ask Dorka about the wheels.")) "two words changed")
    (is (nil? (d/corrected-word {:text "mission" :shown :text} "Ask Dorka now")) "one word, not sent alone")
    (is (nil? (d/corrected-word {:text "rims are done" :shown :text} "Wheels are done")) "a capital at a sentence's start")))

(deftest term-shapes
  (is (d/term? "IR" ["IR"] 0))
  (is (d/term? "v2" ["v2"] 0))
  (is (d/term? "ir-eval" ["ir-eval"] 0))
  (is (not (d/term? "hello" ["hello"] 0)))
  (is (not (d/term? "x" ["x"] 0))))

(deftest my-words-helpers
  (is (d/has-word? ["Iris"] "iris"))
  (is (= ["Iris" "Dorka"] (d/with-word ["Iris"] " Dorka ")))
  (is (= ["Iris"] (d/with-word ["Iris"] "IRIS"))))
