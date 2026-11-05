(ns claudia.slave.video-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.video :as video]))

(deftest which-call
  (is (= {:op :create :chat "c 1"} (video/route "POST" "/api/chats/c%201/videos")))
  (is (= {:op :list :chat "c1"} (video/route "GET" "/api/chats/c1/videos")))
  (is (= {:op :voice :chat "c1" :vid "17-ab"} (video/route "PUT" "/api/chats/c1/videos/17-ab/voice")))
  (is (= {:op :file :chat "c1" :vid "17-ab"} (video/route "GET" "/api/chats/c1/videos/17-ab.mp4")))
  (testing "anything else is not a video call"
    (is (nil? (video/route "GET" "/api/chats/c1/picture.png")))
    (is (nil? (video/route "DELETE" "/api/chats/c1/videos")))
    (is (nil? (video/route "GET" "/api/chats/c1/videos/../x.mp4")))))

(deftest the-scenes-videos
  (let [metas [{:id "1" :block "b" :at 10 :seconds 5} {:id "2" :block "other" :at 30}]
        jobs [{:id "3" :block "b" :at 20 :state "render" :progress 0.5 :chat "c" :data {:big 1}}]]
    (is (= [{:id "3" :block "b" :at 20 :state "render" :progress 0.5}
            {:id "1" :block "b" :at 10 :seconds 5 :state "done"}]
           (video/listing metas jobs "b")))))

(deftest how-far
  (is (= 0.5 (video/progress 150 30 10)))
  (is (= 0.99 (video/progress 900 30 10))))

(deftest where-the-voice-goes
  (let [a (js/Int16Array. 2)]
    (is (= [[1.5 a]] (video/placed [{:text "One." :instruction nil :at 1.5} {:text "Gone." :instruction nil :at 3}]
                                   {[nil "One."] a})))))

(deftest clip-lengths-for-the-page
  (is (= [{:text "One." :instruction "calm" :seconds 0.5}]
         (video/clip-lengths {["calm" "One."] (js/Int16Array. 12000)}))))

(deftest ffmpegs-arguments
  (is (= "pipe:0" (nth (video/frames-args 30 "/o.mp4") 10)))
  (is (= "/o.mp4" (last (video/frames-args 30 "/o.mp4"))))
  (is (= ["-i" "/v.mp4" "-i" "/v.wav"] (subvec (video/mux-args "/v.mp4" "/v.wav" "/o.mp4") 3 7))))
