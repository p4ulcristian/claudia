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

(deftest a-ring-holds-eight-a-fan-six
  (is (= 8 (count (vm/circles {"a" (for [i (range 10)] {:chat (str i) :id "1" :at (inc i)})} 100))))
  (is (= 8 (vm/max-for :ring)))
  (is (= 6 (vm/max-for :up))))

(deftest a-circle-grows-with-what-it-says
  (is (= 30 (vm/size [{:text "Done."}])))
  (is (= 32 (vm/size [{:text (apply str (repeat 200 "a"))} {:text (apply str (repeat 100 "b"))}])))
  (is (= 40 (vm/size [{:text (apply str (repeat 5000 "a"))}])) "never past 40")
  (is (= 31 (vm/size [{:ask {:questions [{:question (apply str (repeat 160 "q"))}]}}])) "an ask counts its questions"))

(deftest a-full-ring-round-a-floating-mic
  (let [ring (vm/spots 8 :ring)]
    (is (= {:x 29 :y -70} (first ring)) "the first just right of straight up")
    (is (= 8 (count (set ring))) "eight places, none twice")
    (is (every? #(< 75 (js/Math.hypot (:x %) (:y %)) 77) ring) "all 76 px out")
    (is (not-any? #(or (zero? (:x %)) (zero? (:y %))) ring) "none straight up, down, left or right")))

(deftest a-fan-above-other-mics
  (let [[a b c] (vm/spots 3 :up)]
    (is (= {:x 26 :y -71} a) "first up and a little right")
    (is (= {:x -26 :y -71} b) "then mirrored left")
    (is (> (:x c) (:x a)) "then further right"))
  (is (every? #(neg? (:y %)) (vm/spots 6 :up)) "all six above the mic")
  (is (= [] (vm/spots 0 :up))))
