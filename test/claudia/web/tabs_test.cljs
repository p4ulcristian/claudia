(ns claudia.web.tabs-test
  "Other tabs of this browser: what a message does, which tabs still live, which chats they show."
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.tabs :as tabs]))

(deftest heard-sets-drops-and-ignores
  (is (= {"b" {:open ["s" "c1"] :view nil}}
         (tabs/heard {} {:kind "here" :from "b" :open ["s" "c1"] :view nil})))
  (is (= {"b" {:open nil :view nil}}
         (tabs/heard {"b" {:open ["s" "c1"]}} {:kind "here" :from "b" :open nil :view nil})))
  (is (= {} (tabs/heard {"b" {:open ["s" "c1"]}} {:kind "bye" :from "b"})))
  (is (= {"b" {:open ["s" "c1"]}} (tabs/heard {"b" {:open ["s" "c1"]}} {:kind "who" :from "c"}))))

(deftest alive-keeps-tabs-whose-lock-is-held
  (is (= {"b" {:open ["s" "c1"]}}
         (tabs/alive {"b" {:open ["s" "c1"]} "c" {:open ["s" "c2"]}}
                     ["claudia-tab:b" "claudia-tab:a" "other-lock" nil]))))

(deftest elsewhere-is-the-same-view-only
  (let [t {"b" {:open ["s" "c1"] :view nil}
           "c" {:open ["s" "c2"] :view "anna"}
           "d" {:open nil :view nil}
           "e" {:open ["s" "c1"] :view nil}}]
    (is (= #{["s" "c1"]} (tabs/elsewhere t nil)))
    (is (= #{["s" "c2"]} (tabs/elsewhere t "anna")))))

(deftest a-message-reads-back
  (let [m (tabs/read-message (tabs/message "here" ["s" "c1"] nil))]
    (is (= "here" (:kind m)))
    (is (= ["s" "c1"] (:open m)))
    (is (string? (:from m))))
  (is (nil? (:open (tabs/read-message (tabs/message "bye" nil nil)))))
  (is (nil? (tabs/read-message #js {:kind 3})))
  (is (nil? (tabs/read-message nil))))
