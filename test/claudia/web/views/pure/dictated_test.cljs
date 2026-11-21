(ns claudia.web.views.pure.dictated-test
  (:require [cljs.test :refer [deftest is]]
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

(deftest my-words-helpers
  (is (d/has-word? ["Iris"] "iris"))
  (is (= ["Iris" "Dorka"] (d/with-word ["Iris"] " Dorka ")))
  (is (= ["Iris"] (d/with-word ["Iris"] "IRIS")))
  (is (= ["Dorka"] (d/without-word ["Iris" "Dorka"] "IRIS"))))

(deftest an-offered-word-s-card-shows-only-when-it-is-new
  (let [prefs {:declined ["Otto"] :learned [] :offers true}
        offer {:word "GitHub" :heard "get hub"}]
    (is (d/card-shown? offer ["Claudia"] prefs))
    (is (not (d/card-shown? offer ["GITHUB"] prefs)) "already in My words, case ignored")
    (is (not (d/card-shown? {:word "otto"} [] prefs)) "said No to before")
    (is (not (d/card-shown? offer [] (assoc prefs :offers false))) "offers switched off")
    (is (not (d/card-shown? {:word " "} [] prefs)) "a blank word")))
