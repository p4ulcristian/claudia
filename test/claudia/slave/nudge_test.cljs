(ns claudia.slave.nudge-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [claudia.slave.chats.nudge :as nudge]
            [claudia.slave.history :as history]
            [claudia.slave.tools :as tools]))

(deftest an-empty-reply-is-blank-or-the-stock-phrase
  (is (true? (nudge/empty-reply? "")))
  (is (true? (nudge/empty-reply? nil)))
  (is (true? (nudge/empty-reply? "  \n ")))
  (is (true? (nudge/empty-reply? "No response requested.")))
  (is (true? (nudge/empty-reply? "no response requested")))
  (is (true? (nudge/empty-reply? " (No response requested.) ")))
  (testing "anything more is a real reply"
    (is (false? (nudge/empty-reply? "No response requested. Merging now.")))
    (is (false? (nudge/empty-reply? "Done.")))
    (is (false? (nudge/empty-reply? "(still running)")))
    (is (false? (nudge/empty-reply? "No response needed from you, all green.")))))

(deftest a-notification-turn-is-stamped-by-claude-code
  (is (true? (nudge/notification-turn? {:origin {:kind "task-notification" :producer "session-task"}})))
  (is (true? (nudge/notification-turn? {:origin {:kind "task-notification"}})))
  (testing "a person's send, a peer's message and the special kinds are not"
    (is (false? (nudge/notification-turn? {})))
    (is (false? (nudge/notification-turn? {:origin {:kind "human"}})))
    (is (false? (nudge/notification-turn? {:origin {:kind "peer" :from "x"}})))
    (is (false? (nudge/notification-turn? {:origin {:kind "task-notification" :subkind "scheduled-trigger"}})))))

(deftest a-notice-and-how-long-it-waits
  (is (= {:id "u" :task "Build" :status "completed" :at 5}
         (nudge/notice-of {:uuid "u" :status "completed" :summary "s"} "Build" 5)))
  (is (nil? (:task (nudge/notice-of {:uuid "u" :status "failed"} "" 5))))
  (testing "a notice that came during the turn waits; one from before it was the turn's"
    (is (= {:at 20} (nudge/kept-notice {:at 20} 10)))
    (is (nil? (nudge/kept-notice {:at 5} 10)))
    (is (nil? (nudge/kept-notice nil 10)))))

(def ^:private quiet-result
  {:type "result" :subtype "success" :is_error false :result "No response requested."
   :origin {:kind "task-notification" :producer "session-task"} :queued_turn_count 0})

(def ^:private facts
  "The incident: the last subagent finished at 100, its notice started the turn at 110, the person
  last wrote at 50, nothing runs any more."
  {:result quiet-result :stopped false :archived false :moving false :working 0
   :notice {:id "n" :task "Combine" :status "completed" :at 100} :since 110 :sent-at 50})

(deftest the-incident-is-nudged
  (is (true? (nudge/nudge? facts)))
  (is (true? (nudge/nudge? (assoc-in facts [:result :result] ""))))
  (is (true? (nudge/nudge? (assoc-in facts [:notice :status] "failed"))))
  (is (true? (nudge/nudge? (update facts :result dissoc :queued_turn_count))) "an older CLI without the count"))

(deftest never-a-nudge
  (testing "for a real reply"
    (is (false? (nudge/nudge? (assoc-in facts [:result :result] "Merged; opening the PR.")))))
  (testing "for a turn a person's message started (no origin) or the nudge itself started"
    (is (false? (nudge/nudge? (update facts :result dissoc :origin)))))
  (testing "for a turn that failed"
    (is (false? (nudge/nudge? (assoc-in facts [:result :is_error] true))))
    (is (false? (nudge/nudge? (assoc-in facts [:result :subtype] "error_during_execution")))))
  (testing "when the person stopped the turn (Stop / Esc)"
    (is (false? (nudge/nudge? (assoc facts :stopped true)))))
  (testing "for an archived or moving chat"
    (is (false? (nudge/nudge? (assoc facts :archived true))))
    (is (false? (nudge/nudge? (assoc facts :moving true)))))
  (testing "while other background work still runs"
    (is (false? (nudge/nudge? (assoc facts :working 2)))))
  (testing "when a sent message waits in Claude Code's queue"
    (is (false? (nudge/nudge? (assoc-in facts [:result :queued_turn_count] 1))))))

(deftest never-a-nudge-twice-or-without-its-notice
  (testing "without a waiting notice (consumed by the first nudge, or never heard)"
    (is (false? (nudge/nudge? (dissoc facts :notice)))))
  (testing "for a task that was stopped"
    (is (false? (nudge/nudge? (assoc-in facts [:notice :status] "stopped")))))
  (testing "for a notice that came after the turn began"
    (is (false? (nudge/nudge? (assoc facts :since 90)))))
  (testing "once a message went in after the notice: the person's, or the nudge itself"
    (is (false? (nudge/nudge? (assoc facts :sent-at 105))))
    (is (false? (nudge/nudge? (assoc facts :sent-at 100))))))

(deftest the-nudge-goes-only-while-the-chat-stays-quiet
  (is (true? (nudge/still-quiet? {:sent-at 1} {:sent-at 1})))
  (is (false? (nudge/still-quiet? {:sent-at 1} {:sent-at 1 :running true})) "a turn started meanwhile")
  (is (false? (nudge/still-quiet? {:sent-at 1} {:sent-at 2})) "a message went in meanwhile"))

(deftest the-nudge-text-names-the-task
  (is (= (str "You ended your turn without a reply after \"Combine\" finished. Continue the plan you"
              " told the user, or say in one line that nothing is left.")
         (nudge/nudge-text "Combine")))
  (is (str/includes? (nudge/nudge-text nil) "after background work finished")))

(deftest the-nudge-shows-as-claudias-note
  (let [tagged (history/with-nudge-tag "Go on.")]
    (is (= "<claudia-nudge>Go on.</claudia-nudge>" tagged))
    (is (= "Claudia: Go on." (history/nudge-note tagged)))
    (is (= "Claudia: Go on." (history/nudge-note (str "  " tagged "\n")))))
  (is (nil? (history/nudge-note "please <claudia-nudge>x</claudia-nudge>")) "only a whole message")
  (is (nil? (history/nudge-note "hello")))
  (testing "read back from the session file, as text and as blocks"
    (is (= [{:id "u" :role "note" :text "Claudia: Go on." :at 0}]
           (history/session-msgs [{:type "user" :uuid "u" :message {:content "<claudia-nudge>Go on.</claudia-nudge>"}}] [] nil)))
    (is (= [{:id "u" :role "note" :text "Claudia: Go on." :at 0}]
           (history/session-msgs [{:type "user" :uuid "u" :message {:content [{:type "text" :text "<claudia-nudge>Go on.</claudia-nudge>"}]}}] [] nil)))))

(deftest the-prompt-says-to-carry-on-after-background-work
  (is (str/includes? tools/system-append "carry on in that same turn with the plan you told the user"))
  (is (str/includes? tools/system-append "An empty reply ends the turn and the work stops.")))

(deftest the-prompt-asks-for-a-first-line-before-any-tool
  (is (str/includes? tools/system-append "before any tool call and before any drawing"))
  (is (str/includes? tools/system-append "the first line is the whole reply")))
