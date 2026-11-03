(ns claudia.web.media-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.media :as media]))

(deftest frame-times-spread-over-the-video
  (is (= [0.5 1.5 2.5] (media/frame-times 3)))
  (is (= 30 (count (media/frame-times 600))))
  (is (= [0.25] (media/frame-times 0.5)))
  (is (= 1 (count (media/frame-times 0)))))

(deftest video-note-says-what-the-frames-are
  (is (= "Video \"a.mp4\" (3.0 s, 640x480): 3 frames follow in order, taken at 0.5 s, 1.5 s, 2.5 s. There is no sound."
         (media/video-note "a.mp4" 3 640 480 [0.5 1.5 2.5])))
  (is (= "Video \"b\" (0.5 s, 1x1): 1 frame follow in order, taken at 0.3 s. There is no sound."
         (media/video-note "b" 0.5 1 1 [0.25]))))
