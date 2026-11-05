(ns claudia.web.scene3d.recording-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.scene3d.recording :as rec]))

(deftest the-video-format
  (testing "the first format the browser offers, MP4 before WebM"
    (is (= "video/mp4;codecs=avc1.640028,mp4a.40.2" (rec/pick-type (constantly true))))
    (is (= "video/webm;codecs=vp9,opus" (rec/pick-type #(boolean (re-find #"^video/webm" %))))))
  (testing "no H.264: WebM, never an MP4 holding VP9"
    (is (= "video/webm;codecs=vp9,opus" (rec/pick-type #(not (re-find #"avc1" %))))))
  (is (nil? (rec/pick-type (constantly false))))
  (is (= "mp4" (rec/extension "video/mp4")))
  (is (= "webm" (rec/extension "video/webm;codecs=vp9,opus"))))

(deftest the-video-size
  (testing "the stage at the pixel ratio, both sides even"
    (is (= [1600 880] (rec/video-size 800 440 2 1920)))
    (is (= [800 440] (rec/video-size 801 441 1 1920))))
  (testing "the long side at most max-side"
    (is (= [1920 1080] (rec/video-size 1920 1080 2 1920))))
  (is (= [2 2] (rec/video-size 0 0 1 1920))))

(deftest a-stage-fits-the-video
  (is (= {:scale 2 :x 0 :y 0} (rec/fit 800 440 1600 880)))
  (testing "a stage made wider keeps its shape, with bands above and below"
    (is (= {:scale 1 :x 0 :y 140} (rec/fit 1600 600 1600 880)))))

(deftest where-a-word-sits
  (is (= 14 (rec/baseline 0 20 12 4)))
  (is (= 112 (rec/baseline 100 16 12 4))))

(deftest words-and-where-they-are
  (is (= [[0 4] [5 7] [9 14]] (rec/word-spans "What is  light")))
  (is (= [] (rec/word-spans "   "))))
