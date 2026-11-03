(ns claudia.hub.pass-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.hub.pass :as pass]))

(deftest request-headers-pass-only-a-few
  (is (= {"x-claudia-email" "ann@example.com" "x-claudia-view" "paul" "content-type" "text/plain"}
         (pass/request-headers #js {"content-type" "text/plain" "cookie" "login=1"} "ann@example.com" "paul")))
  (is (= {"x-claudia-email" "ann@example.com"} (pass/request-headers #js {} "ann@example.com" nil))))

(deftest response-headers-drop-hop-by-hop-and-cookies
  (is (= {"content-type" "text/html" "etag" "x"}
         (js->clj (pass/response-headers #js {"content-type" "text/html" "connection" "keep-alive"
                                              "set-cookie" "a=1" "etag" "x"})))))

(deftest closing-code-passes-what-may-be-sent
  (is (= 1000 (pass/closing-code 1000 true)))
  (is (= 4001 (pass/closing-code 4001 false)))
  (is (= 1014 (pass/closing-code 1006 true)))
  (is (= 1001 (pass/closing-code 1006 false)))
  (is (nil? (pass/closing-code 1005 true))))

(deftest front-url-brackets-ipv6
  (is (= "ws://10.0.0.2:4540/ws" (pass/front-url {:host "10.0.0.2" :port 4540} "/ws")))
  (is (= "ws://[fd00::2]:4540/ws?view=a" (pass/front-url {:host "fd00::2" :port 4540} "/ws?view=a"))))

(deftest upgrade-refusal-is-plain-http
  (is (= (str "HTTP/1.1 404 Not Found\r\ncontent-type: text/plain\r\ncontent-length: 2\r\n"
              "cache-control: no-store\r\nconnection: close\r\n\r\nno")
         (pass/upgrade-refusal 404 "no" "text/plain"))))
