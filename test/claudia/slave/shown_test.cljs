(ns claudia.slave.shown-test
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [async deftest is testing]]
            [claudia.slave.api :as api]
            [claudia.slave.history :as history]
            [claudia.slave.shown :as shown]))

(deftest which-bytes-a-range-asks-for
  (is (nil? (shown/byte-range nil 100)) "no header: the whole file")
  (is (= {:start 0 :end 1} (shown/byte-range "bytes=0-1" 100)) "Safari's first probe")
  (is (= {:start 10 :end 99} (shown/byte-range "bytes=10-" 100)))
  (is (= {:start 10 :end 99} (shown/byte-range "bytes=10-500" 100)) "an end past the file is cut")
  (is (= {:start 90 :end 99} (shown/byte-range "bytes=-10" 100)) "the last 10")
  (is (= {:start 0 :end 99} (shown/byte-range "bytes=-500" 100)))
  (testing "ranges that can't be served"
    (is (= :bad (shown/byte-range "bytes=100-" 100)))
    (is (= :bad (shown/byte-range "bytes=5-2" 100)))
    (is (= :bad (shown/byte-range "bytes=-0" 100))))
  (testing "headers this doesn't read: the whole file"
    (is (nil? (shown/byte-range "bytes=0-1,5-9" 100)))
    (is (nil? (shown/byte-range "items=0-1" 100)))
    (is (nil? (shown/byte-range "bytes=-" 100)))))

(deftest a-stored-videos-key
  (is (= "17-0a0b0c0d.mp4" (shown/new-key 17 "0a0b0c0d" ".mp4")))
  (is (= "17-0a0b0c0d.mp4" (shown/key-in "Shown in the chat: a.mp4 (a run). [video 17-0a0b0c0d.mp4]")))
  (is (nil? (shown/key-in "Shown in the chat: a.png.")))
  (is (nil? (shown/key-in "[video ../../etc/passwd]")) "only a key this made")
  (is (= "Shown in the chat: a.mp4 (the run). [video k]" (shown/video-answer "a.mp4" "k" "the run")))
  (is (= "Shown in the chat: a.mp4. [video k]" (shown/video-answer "a.mp4" "k" nil))))

(deftest what-show-video-refuses
  (is (nil? (shown/video-refusal "/v/a.MP4" {:file? true :size 10})))
  (is (re-find #"not an mp4" (shown/video-refusal "/v/a.png" {:file? true :size 10})))
  (is (re-find #"No file" (shown/video-refusal "/v/a.mp4" nil)))
  (is (re-find #"up to" (shown/video-refusal "/v/a.webm" {:file? true :size (inc shown/video-max)}))))

(deftest the-runner-finds-a-steps-video
  (let [result #js {:type "tool_result" :tool_use_id "t1"
                    :content #js [#js {:type "text" :text "Shown in the chat: a.mp4. [video 17-0a0b0c0d.mp4]"}]}
        line (js/JSON.stringify #js {:type "user" :message #js {:content #js [result]}})]
    (is (= "17-0a0b0c0d.mp4" (api/video-of-line line "t1")))
    (is (nil? (api/video-of-line line "t2")))))

(deftest a-video-step-is-flagged
  (let [[[tool]] (history/read-assistant {:id "m3" :content [{:type "tool_use" :id "t9" :name "mcp__workspace__show_video"
                                                              :input {:path "/v/run.mp4" :caption "the run"}}]} {} 1 nil)]
    (is (:video tool))
    (is (= "video the run" (:summary tool)))))

(defn- get-range
  "Promise of {:status :headers :body} for GET path on port, with the Range header r (nil: none)."
  [port p r]
  (js/Promise.
   (fn [done]
     (http/get #js {:port port :path p :headers (if r #js {:range r} #js {})}
               (fn [^js res]
                 (let [parts #js []]
                   (.on res "data" #(.push parts %))
                   (.on res "end" #(done {:status (.-statusCode res) :headers (.-headers res)
                                          :body (.toString (js/Buffer.concat parts))}))))))))

(deftest a-stored-video-is-served-in-parts
  (async finish
    (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "shown-"))
          src (path/join dir "clip.mp4")
          _ (fs/writeFileSync src "0123456789")
          _ (shown/set-root! dir)
          server (http/createServer (fn [req res] (shown/serve! req res "c1" (.slice (.-url req) 1))))]
      (.listen server 0 "127.0.0.1"
               (fn []
                 (let [port (.-port (.address server))]
                   (-> (shown/store! "c1" src)
                       (.then (fn [k]
                                (js/Promise.all #js [(get-range port (str "/" k) nil) (get-range port (str "/" k) "bytes=0-1")
                                                     (get-range port (str "/" k) "bytes=20-") (get-range port "/17-00000000.mp4" nil)])))
                       (.then (fn [[whole part bad none]]
                                (is (= [200 "0123456789" "video/mp4"] [(:status whole) (:body whole) (aget (:headers whole) "content-type")]))
                                (is (= [206 "01" "bytes 0-1/10"] [(:status part) (:body part) (aget (:headers part) "content-range")]))
                                (is (= 416 (:status bad)))
                                (is (= 404 (:status none)))))
                       (.catch #(is false (str %)))
                       (.finally (fn [] (.close server) (fs/rmSync dir #js {:recursive true}) (finish))))))))))
