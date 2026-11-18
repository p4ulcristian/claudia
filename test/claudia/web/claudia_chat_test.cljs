(ns claudia.web.claudia-chat-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.claudia-chat :as cc]
            [claudia.web.subs :as subs]))

(deftest her-server-is-the-one-whose-runner-has-her
  (let [db {:me {:servers [{:name "box"} {:name "destiny"}]} :meta {"destiny" {:claudia true} "box" {}}}]
    (is (= "destiny" (cc/her-server db)))
    (is (nil? (cc/her-server (assoc db :view "peter"))) "another's chats: she opens nothing")
    (is (nil? (cc/her-server (assoc-in db [:meta "destiny" :claudia] false))))))

(deftest her-chat-is-in-no-list
  (is (= ["a"] (map :id (subs/list-rows {"s" {:status :open :rows {"a" {:id "a"} "c" {:id "c" :claudia true}}}} false #{})))))
