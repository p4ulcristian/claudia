(ns claudia.web.download-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.download :as d]))

(deftest extensions-of-types
  (is (= "jpg" (d/ext-of-type "image/jpeg")))
  (is (= "mp4" (d/ext-of-type "Video/MP4; codecs=avc1")))
  (is (= "mov" (d/ext-of-type "video/quicktime")))
  (is (nil? (d/ext-of-type "application/octet-stream")))
  (is (nil? (d/ext-of-type nil))))

(deftest names-to-save-as
  (is (= "shot.png" (d/save-name "/home/x/shot.png" "image/png" "image")))
  (is (= "shot.jpg" (d/save-name "/home/x/shot.png" "image/jpeg" "image")) "the type wins")
  (is (= "photo-2.webp" (d/save-name "photo-2" "image/webp" "image")))
  (is (= "clip.mov" (d/save-name "clip.mov" "application/octet-stream" "video")) "its own when the type says nothing")
  (is (= "image.png" (d/save-name nil "image/png" "image")))
  (is (= "file" (d/save-name "" "" "file"))))

(deftest apple-touch-devices
  (is (d/apple-touch? "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)" "iPhone" 5))
  (is (d/apple-touch? "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)" "MacIntel" 5) "an iPad asks for the desktop site")
  (is (not (d/apple-touch? "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)" "MacIntel" 0)) "a Mac")
  (is (not (d/apple-touch? "Mozilla/5.0 (Linux; Android 15)" "Linux armv8l" 5))))
