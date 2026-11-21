(ns claudia.web.views.pure.voicemail-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.voicemail :as vm]))

(def ^:private day (* 24 60 60 1000))

(deftest one-circle-per-chat-the-longest-waiting-first
  (let [now (* 10 day)
        by-server {"a" [{:chat "c" :id "2" :at (- now 50)} {:chat "c" :id "1" :at (- now 100)}
                        {:chat "d" :id "1" :at (- now 70)}]
                   "b" [{:chat "c" :id "1" :at (- now 200)} {:chat "old" :id "1" :at (- now day)}]}
        cs (vm/circles by-server now)]
    (is (= [["b" "c"] ["a" "c"] ["a" "d"]] (mapv :k cs)) "chats of two servers stay apart; a day old is gone")
    (is (= ["1" "2"] (mapv :id (:msgs (second cs)))) "a chat's messages oldest first")
    (is (= (- now 100) (:at (second cs))))))

(deftest at-most-six-circles
  (is (= 6 (count (vm/circles {"a" (for [i (range 10)] {:chat (str i) :id "1" :at (inc i)})} 100)))))

(deftest a-circle-grows-with-what-it-says
  (is (= 30 (vm/size [{:text "Done."}])))
  (is (= 32 (vm/size [{:text (apply str (repeat 200 "a"))} {:text (apply str (repeat 100 "b"))}])))
  (is (= 40 (vm/size [{:text (apply str (repeat 5000 "a"))}])) "never past 40")
  (is (= 31 (vm/size [{:ask {:questions [{:question (apply str (repeat 160 "q"))}]}}])) "an ask counts its questions"))

(deftest circles-fan-out-leaving-the-middle-free
  (let [[a b c d] (vm/spots 4 :left)]
    (is (= {:x -58 :y -49} a) "the first up and to the left of the rail's mic")
    (is (= {:x -58 :y 49} b) "the second mirrored below it")
    (is (< (:y c) (:y a)) "the third further up")
    (is (> (:y d) (:y b)) "the fourth further down")
    (is (every? #(< (:x %) 44) (vm/spots 6 :left)) "every centre inside the rail's half-width"))
  (let [[a b] (vm/spots 2 :up)]
    (is (= {:x 49 :y -58} a) "off the rail: above, to the right")
    (is (= {:x -49 :y -58} b) "then to the left"))
  (is (= [] (vm/spots 0 :up))))

(deftest a-finger-on-a-circle
  (is (= :play (vm/gesture 3 2 120)))
  (is (= :dismiss (vm/gesture 30 25 120)) "moved: swiped away")
  (is (= :open (vm/gesture 2 2 700)) "held still: opens its chat"))
