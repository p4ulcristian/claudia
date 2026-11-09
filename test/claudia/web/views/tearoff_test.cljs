(ns claudia.web.views.tearoff-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.tearoff :as tearoff]))

(deftest leaving-at-the-edge-leaves-the-window
  (let [win {:w 1000 :h 800 :related? false}]
    (is (true? (tearoff/left-window? (assoc win :x 3 :y 400))))
    (is (true? (tearoff/left-window? (assoc win :x 500 :y 790))))
    (is (true? (tearoff/left-window? (assoc win :x 1200 :y 400))))
    (testing "into a frame in the middle: still in the window"
      (is (false? (tearoff/left-window? (assoc win :x 500 :y 400)))))
    (testing "leaving to another element is no leaving"
      (is (false? (tearoff/left-window? (assoc win :x 3 :y 400 :related? true)))))))

(deftest the-new-window
  (is (= "https://x.test/c/destiny/a%20b" (tearoff/chat-url "https://x.test" ["destiny" "a b"])))
  (is (= "popup,width=1201,height=800" (tearoff/features 1200.6 800))))
