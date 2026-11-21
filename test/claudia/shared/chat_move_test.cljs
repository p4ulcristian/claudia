(ns claudia.shared.chat-move-test
  "Moving a chat to another server (claudia.shared.chat-move), on plain data."
  (:require [cljs.test :refer [deftest is]]
            [claudia.shared.chat-move :as cm]))

(def ^:private id "0b5e7f3a-1c2d-4e5f-8a9b-0c1d2e3f4a5b")

(deftest chat-id-is-a-lower-case-uuid
  (is (cm/chat-id? id))
  (is (not (cm/chat-id? (.toUpperCase id))))
  (is (not (cm/chat-id? "../etc")))
  (is (not (cm/chat-id? nil))))

(deftest rel-ok-takes-only-the-session-files
  (is (cm/rel-ok? id (str id ".jsonl")))
  (is (cm/rel-ok? id (str id "/subagents/agent-a1.jsonl")))
  (is (cm/rel-ok? id (str id "/tool-results/toolu_01.txt")))
  (is (not (cm/rel-ok? id (str id "/../other.jsonl"))) "no climbing out")
  (is (not (cm/rel-ok? id (str id "/./x"))))
  (is (not (cm/rel-ok? id (str id "/.hidden"))))
  (is (not (cm/rel-ok? id (str id "//x"))) "no empty part")
  (is (not (cm/rel-ok? id "other.jsonl")))
  (is (not (cm/rel-ok? id (str id "/a/b/c/d/e/f"))) "at most five folders deep")
  (is (not (cm/rel-ok? id (str "/" id ".jsonl"))) "never absolute")
  (is (not (cm/rel-ok? "x" "x.jsonl")) "only a chat id's"))

(deftest request-problem-names-what-is-wrong
  (let [servers ["home" "work"]]
    (is (nil? (cm/request-problem {:chat id :from "home" :to "work"} servers)))
    (is (nil? (cm/request-problem {:chat id :from "home" :to "work" :folder "~/Work/app"} servers)))
    (is (= "chat: a chat id" (cm/request-problem {:chat "x" :from "home" :to "work"} servers)))
    (is (= "not one of your servers: gate" (cm/request-problem {:chat id :from "home" :to "gate"} servers)))
    (is (= "the chat is on that server already" (cm/request-problem {:chat id :from "home" :to "home"} servers)))
    (is (= "folder: a path or nothing" (cm/request-problem {:chat id :from "home" :to "work" :folder 3} servers)))))

(deftest messages-check-counts-what-people-and-claude-wrote
  (is (= {:messages 3 :last "u3"}
         (cm/messages-check [{:type "summary"} {:type "user" :uuid "u1"} {:type "assistant" :uuid "u2"}
                             {:type "system" :uuid "s1"} {:type "user" :uuid "u3"} {:type "assistant"}])))
  (is (= {:messages 0 :last nil} (cm/messages-check []))))

(def ^:private src
  {:messages 4 :last "u4" :files [{:path "a.jsonl" :size 10 :sha256 "aa"} {:path "a/s/b.jsonl" :size 3 :sha256 "bb"}]
   :pictures 2})

(deftest check-problem-passes-only-the-same-chat
  (is (nil? (cm/check-problem "home" "work" src src)))
  (is (nil? (cm/check-problem "home" "work" src (update src :files (comp vec reverse)))) "file order does not count")
  (is (= "work gave no check of its copy." (cm/check-problem "home" "work" src nil)))
  (is (= "The copy on work holds 3 messages, the chat on home 4."
         (cm/check-problem "home" "work" src (assoc src :messages 3))))
  (is (= "The copy on work ends on another message than the chat on home."
         (cm/check-problem "home" "work" src (assoc src :last "u9"))))
  (is (= "The copy of a/s/b.jsonl on work is not the one on home."
         (cm/check-problem "home" "work" src (assoc-in src [:files 1 :sha256] "cc"))))
  (is (= "The copy of a/s/b.jsonl on work is not the one on home."
         (cm/check-problem "home" "work" src (update src :files #(subvec % 0 1)))) "a missing file")
  (is (= "The copy on work has 1 pictures, home 2." (cm/check-problem "home" "work" src (assoc src :pictures 1)))))
