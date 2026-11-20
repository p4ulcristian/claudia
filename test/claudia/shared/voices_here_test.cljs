(ns claudia.shared.voices-here-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.colours :as colours]
            [claudia.shared.order :as order]
            [claudia.shared.protocol :as p]
            [claudia.shared.voices :as voices]
            [claudia.web.here :as here]))

(deftest voices-are-ids-or-off
  (is (= "nora" (voices/voice-id "nora")))
  (is (= "" (voices/voice-id "nobody")))
  (is (= "" (voices/voice-id nil)))
  (is (= voices/default-id (:id (first voices/all))))
  (is (apply distinct? (map :id voices/all))))

(deftest the-page-touched-last-speaks
  (let [pages [{:n 1 :at 100} {:n 2 :at 300} {:n 3 :at 0}]]
    (is (= 2 (:n (p/last-touched pages :at))))
    (is (nil? (p/last-touched [{:at 0}] :at)) "never touched: none")
    (is (nil? (p/last-touched [] :at)))))

(deftest here-frames-say-when
  (is (= 900 (p/touched-at 1000 100)))
  (is (= 0 (p/touched-at 1000 -5)) "from the future: never wins")
  (is (= 0 (p/touched-at 1000 "x")))
  (is (= 0 (p/touched-at 1000 nil)))
  (is (= {:type "here" :ago 50} (here/frame-at 950 1000)))
  (is (nil? (here/frame-at 0 1000)) "never touched")
  (is (nil? (here/frame-at 1 (* 2 24 60 60 1000))) "too long ago"))

(deftest group-picks-keep-the-voice
  (is (= {:voice "otto" :bell false} (order/clean-picks {:voice "otto" :bell false :x 1})))
  (is (= {:voice "nora"} (order/clean-picks {:talk true})) "a speaker on before voices: the first voice")
  (is (= {:voice ""} (order/clean-picks {:talk true :voice ""})))
  (is (= {} (order/clean-picks nil))))

(deftest every-colour-has-a-short-name
  (is (every? colours/name-of (conj colours/chat-colours "ash")))
  (is (= 36 (count colours/chat-colours)))
  (is (apply distinct? (map colours/name-of colours/chat-colours)) "no two colours sound the same")
  (is (= "Red" (colours/name-of "cherry")))
  (is (nil? (colours/name-of "neon"))))

(deftest a-new-chat-takes-a-colour-no-open-chat-wears
  (let [chats (map-indexed (fn [i c] {:id (str i) :color c :started i}) (take 19 colours/solid-colours))
        last-solid (last colours/solid-colours)]
    (is (= last-solid (colours/most-different chats nil)) "the one free solid")
    (is (colours/gradient? (colours/most-different (conj chats {:id "x" :color last-solid :started 30}) nil))
        "no solid free: a gradient")
    (is (= #{"cherry"} (colours/taken [{:id "a" :color "cherry"} {:id "b" :color "ocean" :archived true}] nil)))
    (is (= [{:id "b" :color "cherry" :started 2}]
           (colours/duplicates [{:id "a" :color "cherry" :started 1} {:id "b" :color "cherry" :started 2}
                                {:id "c" :color "ash" :started 3} {:id "d" :color "ash" :started 4}]))
        "the newer wears it twice; grey may be many")))

(deftest every-colour-taken-still-gives-one
  (let [chats (map-indexed (fn [i c] {:id (str i) :color c :started i}) colours/chat-colours)]
    (is (colours/chat-colour? (colours/most-different chats nil)))))
