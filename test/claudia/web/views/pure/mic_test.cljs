(ns claudia.web.views.pure.mic-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.mic :as mic]))

(deftest heard-text-joins-the-draft
  (is (= "hello" (mic/with-heard "" " hello ")))
  (is (= "fix it please" (mic/with-heard "fix it" "please")))
  (is (= "line\nnext" (mic/with-heard "line\n" "next")))
  (is (= "kept" (mic/with-heard "kept" "  ")))
  (is (= "x" (mic/with-heard nil "x"))))

(deftest bars-are-mirrored-levels
  (let [levels (mic/bar-levels (vec (repeat 256 255)))]
    (is (= mic/bar-count (count levels)))
    (is (every? #(= 1 %) levels)))
  (let [levels (mic/bar-levels (vec (concat (repeat 8 255) (repeat 248 0))))]
    (is (= (first levels) (last levels)))
    (is (pos? (first levels)))
    (is (zero? (nth levels 20)))))

(deftest bars-ease
  (is (= [0.6 0.8] (mic/eased [0 1] [1 0])))
  (is (= [0.6] (mic/eased nil [1]))))

(deftest a-drag-right-adds-anything-else-sends
  (is (= :send (mic/gesture 0 0)))
  (is (= :send (mic/gesture -120 0)) "dragged left")
  (is (= :send (mic/gesture 0 80)) "dragged down")
  (is (= :send (mic/gesture (dec mic/drag-px) 0)))
  (is (= :add (mic/gesture mic/drag-px 0)))
  (is (= :add (mic/gesture 200 -60)) "more right than up")
  (is (= :cancel (mic/gesture 0 (- mic/drag-px))))
  (is (= :cancel (mic/gesture 30 -90)) "more up than right")
  (is (= :send (mic/gesture 0 (- 1 mic/drag-px))) "not far enough up"))

(deftest heard-clips-go-out-in-spoken-order
  (let [l0 {:next 0 :held {}}
        [l1 out1] (mic/in-order l0 1 {:text "second"})
        [l2 out2] (mic/in-order l1 0 {:text "first"})]
    (is (= [] out1) "the second clip, heard first, waits")
    (is (= [{:text "first"} {:text "second"}] out2) "the first one lets both out, in order")
    (is (= {:next 2 :held {}} l2)))
  (let [[l1 out1] (mic/in-order {:next 0 :held {}} 1 {:text "b"})
        [_ out2] (mic/in-order l1 0 nil)]
    (is (= [] out1))
    (is (= [{:text "b"}] out2) "a failed earlier clip holds nothing back")))
