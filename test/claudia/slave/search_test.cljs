(ns claudia.slave.search-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.search :as search]))

(deftest said-reads-what-the-user-and-claude-said
  (is (= {:uuid "u1" :role "user" :text "hello"}
         (search/said "{\"type\":\"user\",\"uuid\":\"u1\",\"message\":{\"content\":\"hello\"}}")))
  (is (= {:uuid "a1" :role "claude" :text "hi\nthere"}
         (search/said "{\"type\":\"assistant\",\"uuid\":\"a1\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"},{\"type\":\"tool_use\"},{\"type\":\"text\",\"text\":\"there\"}]}}")))
  (testing "a slash command is what was typed; its output alone is nothing"
    (is (= "/model opus" (:text (search/said "{\"type\":\"user\",\"uuid\":\"u2\",\"message\":{\"content\":\"<command-name>model</command-name><command-args>opus</command-args>\"}}"))))
    (is (nil? (search/said "{\"type\":\"user\",\"uuid\":\"u3\",\"message\":{\"content\":\"<local-command-stdout>ok</local-command-stdout>\"}}"))))
  (testing "a task notice, a sidechain line and other lines say nothing"
    (is (nil? (search/said "{\"type\":\"user\",\"uuid\":\"u4\",\"message\":{\"content\":\"<task-notification>x</task-notification>\"}}")))
    (is (nil? (search/said "{\"type\":\"user\",\"uuid\":\"u5\",\"isSidechain\":true,\"message\":{\"content\":\"hi\"}}")))
    (is (nil? (search/said "{\"type\":\"system\",\"uuid\":\"s\"}"))))
  (testing "a message sent mid-turn is the user's, under the sent uuid"
    (is (= {:uuid "sent" :role "user" :text "more"}
           (search/said "{\"type\":\"attachment\",\"uuid\":\"own\",\"attachment\":{\"type\":\"queued_command\",\"prompt\":\"more\",\"source_uuid\":\"sent\"}}")))))

(deftest words-and-query
  (is (= ["héllo" "w0rld_x"] (search/words "Héllo, w0rld_x!")))
  (is (= "\"a\"* \"b\"*" (search/fts-query ["a" "b"]))))

(deftest ranked-puts-titled-chats-first
  (let [chats [{:id "c1" :title "Other"} {:id "c2" :title "Red car"} {:id "c3" :title "x"}]
        rows [{:session "c1" :uuid "1" :role "user" :snippet "a" :rank -5}
              {:session "c1" :uuid "2" :role "user" :snippet "b" :rank -4}
              {:session "c3" :uuid "3" :role "claude" :snippet "c" :rank -9}
              {:session "gone" :uuid "4" :role "user" :snippet "d" :rank -10}]]
    (is (= [{:id "c2" :hits [] :count 0 :title true}
            {:id "c3" :hits [{:uuid "3" :role "claude" :snippet "c"}] :count 1 :title false}
            {:id "c1" :hits [{:uuid "1" :role "user" :snippet "a"} {:uuid "2" :role "user" :snippet "b"}]
             :count 2 :title false}]
           (search/ranked rows chats ["red" "car"])))))
