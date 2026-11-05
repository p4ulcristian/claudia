(ns claudia.web.scene3d.video-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.video :as video]))

(deftest the-video-size
  (testing "the stage at the pixel ratio, both sides even"
    (is (= [1600 880] (video/video-size 800 440 2 1920)))
    (is (= [800 440] (video/video-size 801 441 1 1920))))
  (testing "the long side at most max-side"
    (is (= [1920 1080] (video/video-size 1920 1080 2 1920))))
  (is (= [2 2] (video/video-size 0 0 1 1920))))

(deftest a-stage-fits-the-video
  (is (= {:scale 2 :x 0 :y 0} (video/fit 800 440 1600 880)))
  (testing "a stage made wider keeps its shape, with bands above and below"
    (is (= {:scale 1 :x 0 :y 140} (video/fit 1600 600 1600 880)))))

(deftest where-a-word-sits
  (is (= 14 (video/baseline 0 20 12 4)))
  (is (= 112 (video/baseline 100 16 12 4))))

(deftest words-and-where-they-are
  (is (= [[0 4] [5 7] [9 14]] (video/word-spans "What is  light")))
  (is (= [] (video/word-spans "   "))))

(deftest how-long-the-story-runs
  (let [step {:say "one two three" :hold 0 :pose nil :move 1.4}]
    (testing "a clip sets a step's length, its hold is the least, the gap follows"
      (is (= 4.45 (video/step-seconds step 4)))
      (is (= 6.45 (video/step-seconds (assoc step :hold 6) 4))))
    (testing "without a clip, the estimate"
      (is (< (- (video/step-seconds step nil) (+ 0.6 (/ 3 2.6) 0.45)) 1e-9)))
    (is (= 8.9 (video/story-seconds [step step] (constantly 4))))
    (is (= video/still-seconds (video/story-seconds [] (constantly 4))))))

(deftest what-the-bar-shows
  (let [vs [{:id "a" :state "done" :at 1} {:id "b" :state "failed" :at 2 :error "x"} {:id "c" :state "render" :progress 0.42 :at 3}]]
    (is (= {:making (nth vs 2) :done (first vs) :failed nil} (video/latest vs)))
    (testing "a failure newer than the last video shows when nothing is being made"
      (is (= (second vs) (:failed (video/latest (take 2 vs))))))
    (is (= {:making nil :done nil :failed nil} (video/latest nil))))
  (is (= "video 42%" (video/making-text {:state "render" :progress 0.42})))
  (is (= "voicing" (video/making-text {:state "voice"}))))

(deftest a-video-just-finished
  (let [before [{:id "a" :state "render"} {:id "b" :state "done"}]
        after [{:id "a" :state "done"} {:id "b" :state "done"} {:id "c" :state "done"}]]
    (is (= ["a"] (map :id (video/just-done before after))))
    (is (empty? (video/just-done nil after)))))
