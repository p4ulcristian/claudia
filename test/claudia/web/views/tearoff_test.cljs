(ns claudia.web.views.tearoff-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.tearoff :as tearoff]))

(deftest the-new-window
  (is (= "https://x.test/c/destiny/a%20b" (tearoff/chat-url "https://x.test" ["destiny" "a b"])))
  (is (= "popup,width=1201,height=800" (tearoff/features 1200.6 800))))

(deftest next-free-skips-the-torn-and-the-open-elsewhere
  (let [rows [{:server "d" :id "a"} {:server "d" :id "b"} {:server "box" :id "c"}]]
    (is (= ["box" "c"] (tearoff/next-free rows #{["d" "b"]} ["d" "a"])))
    (is (= ["d" "b"] (tearoff/next-free rows #{} ["d" "a"])))
    (is (nil? (tearoff/next-free rows #{["d" "b"] ["box" "c"]} ["d" "a"])))))
