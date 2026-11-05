(ns claudia.hub.videos-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.videos :as videos]))

(deftest the-runners-path
  (is (= "/api/chats/a%20b/videos" (videos/runner-path "a b")))
  (is (= "/api/chats/c/videos/17-x/voice" (videos/runner-path "c" "/" "17-x" "/voice"))))
