(ns claudia.slave.claudia-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.chat-ops :as ops]
            [claudia.slave.claudia :as claudia]))

(deftest she-is-on-only-with-all-four-settings
  (is (= {:url "u" :server "s" :key-file "k" :email "e"}
         (claudia/config #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s" :CLAUDIA_HUB_KEY_FILE "k" :CLAUDIA_OWNER_EMAIL "e"})))
  (is (nil? (claudia/config #js {:CLAUDIA_HUB_URL "u" :CLAUDIA_SERVER_NAME "s" :CLAUDIA_HUB_KEY_FILE "k"}))))

(deftest her-tools-become-hub-operations
  (is (= ["chats" {}] (claudia/hub-op "list_chats" {:x 1})))
  (is (= ["new" {:server "box" :text "hi"}] (claudia/hub-op "start_chat" {:server "box" :text "hi" :chat "no"})))
  (is (= ["archive" {:server "box" :chat "c" :archived true}] (claudia/hub-op "archive_chat" {:server "box" :chat "c"})))
  (is (= ["archive" {:server "box" :chat "c" :archived false}] (claudia/hub-op "archive_chat" {:server "box" :chat "c" :archived false})))
  (is (= ["stop" {:server "box" :chat "c"}] (claudia/hub-op "stop_chat" {:server "box" :chat "c"})))
  (is (every? #(.startsWith % "mcp__claudia__") claudia/allowed)))

(deftest chat-operations-route-and-check
  (is (= :chats (ops/route "GET" "/api/chat-ops/chats")))
  (is (= :archive (ops/route "POST" "/api/chat-ops/archive")))
  (is (nil? (ops/route "GET" "/api/chat-ops/archive")))
  (is (= "text is needed" (ops/problem :new {:text " "} (constantly true))))
  (is (= "no such chat" (ops/problem :stop {:chat "x"} #{"y"})))
  (is (nil? (ops/problem :send {:chat "y" :text "hi"} #{"y"}))))
