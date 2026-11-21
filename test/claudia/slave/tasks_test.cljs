(ns claudia.slave.tasks-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.slave.tasks :as tasks]))

(deftest line-counts-of-an-edit
  (is (= {:added 2 :removed 1} (tasks/line-counts "a\nb\n" "a\nc\nd\n")))
  (is (= {:added 1 :removed 0} (tasks/line-counts nil "x\n")))
  (is (= {:added 0 :removed 0} (tasks/line-counts "same\n" "same\n"))))

(deftest loaded-stops-what-a-gone-session-ran
  (let [saved [{:id "a" :status "running"} {:id "b" :status "running" :end 3} {:id "c" :status "completed"}]]
    (is (= {"a" {:id "a" :status "stopped" :end 9} "b" {:id "b" :status "stopped" :end 3}
            "c" {:id "c" :status "completed"}}
           (tasks/loaded saved false 9)))
    (is (= "running" (get-in (tasks/loaded saved true 9) ["a" :status])))))

(deftest patched-changes-or-adds-a-task
  (is (= {"a" {:id "a" :status "running" :start 9 :end nil}}
         (tasks/patched {"a" {:id "a" :status "completed" :start 1 :end 2}} "a" {:status "running"} 9))
      "a finished task that runs again restarts its clock")
  (is (= {"x" {:id "x" :name "Agent" :type "task" :background true :start 9 :active 9 :status "running"}}
         (tasks/patched {} "x" {:status "running"} 9)))
  (is (= {} (tasks/patched {} "x" {:status "completed"} 9)) "an unknown finished task is not added"))

(deftest started-keeps-what-its-call-said
  (let [t (tasks/started {:task_id "t" :tool_use_id "u" :description "Look" :is_backgrounded true}
                         {:tool "Agent" :input {:description "D" :prompt "P" :model "m"}} 5)]
    (is (= {:id "t" :use "u" :name "Look" :type "task" :status "running" :background true :start 5 :active 5
            :parent nil :ambient false :prompt "P" :call-name "D" :command nil :model "m"}
           t))))

(deftest with-live-set-ends-the-missing
  (let [ts {"a" {:id "a" :status "running" :background true}
            "b" {:id "b" :status "running" :background true}
            "f" {:id "f" :status "running"}}
        out (tasks/with-live-set ts {"b" {:ambient true} "n" {:description "New"}} 7)]
    (is (= {:id "a" :status "stopped" :background true :end 7} (out "a")))
    (is (= {:id "b" :status "running" :background true :ambient true} (out "b")))
    (is (= "running" (:status (out "f"))) "a foreground task is not in the set")
    (is (= {:id "n" :name "New" :type "task" :status "running" :background true :start 7 :active 7
            :parent nil :ambient false}
           (out "n")))))

(deftest without-gone-ends-only-lost-background-tasks
  (let [ts {"a" {:id "a" :status "running" :background true}
            "b" {:id "b" :status "running" :background true}
            "c" {:id "c" :status "completed" :background true :end 2}
            "f" {:id "f" :status "running"}}
        out (tasks/without-gone ts #{"b"} 7)]
    (is (= {:id "a" :status "stopped" :background true :end 7} (out "a")) "lost: ended at the Stop")
    (is (= (ts "b") (out "b")) "still in flight")
    (is (= (ts "c") (out "c")) "already ended")
    (is (= (ts "f") (out "f")) "a foreground task is not in the set")
    (is (= ts (tasks/without-gone ts #{"a" "b"} 7)) "nothing lost: unchanged")))

(deftest patches-from-task-messages
  (is (= {:status "running" :active 3 :tokens 10 :tools 2 :name "Now" :last-tool "Bash"}
         (tasks/progress-patch {:usage {:total_tokens 10 :tool_uses 2} :description "Now" :last_tool_name "Bash"} 3)))
  (is (= {:status "stopped" :end 3 :background false}
         (tasks/updated-patch {:patch {:status "killed" :is_backgrounded false}} 3)))
  (is (= {:status "running"} (tasks/updated-patch {:patch {:status "pending"}} 3)))
  (is (= {:status "completed" :end 3 :summary "s" :output "o"}
         (tasks/notification-patch {:summary "s" :output_file "o"} 3)))
  (is (= {"a" {:status "stopped" :end 4} "b" {:status "completed"}}
         (tasks/all-stopped {"a" {:status "running"} "b" {:status "completed"}} 4))))

(deftest last-progress-reads-the-last-line
  (is (= {:done 3 :total 10 :label "files"} (tasks/last-progress "x\n##progress 1/10\r##progress 3/10 files\nmore")))
  (is (= {:done 10 :total 10 :label ""} (tasks/last-progress "##progress 12/10")))
  (is (nil? (tasks/last-progress "##progress 1/0"))))

(deftest agent-row-and-order
  (let [t {:id "t" :status "running" :type "local_bash" :background true :start 1 :command "npm test\nmore"}
        row (tasks/agent-row t nil {"t" t} [t])]
    (is (= "shell" (:kind row)))
    (is (= "$ npm test" (:step row)))
    (is (= "t" (:task row)))
    (is (= 0 (:children row)))
    (is (= 0 (:tools row)))))

(deftest agent-view-reads-a-subagent-file
  (let [lines [{:type "user" :message {:content "Do the thing"}}
               {:type "assistant" :timestamp "1970-01-01T00:00:01.000Z"
                :message {:id "m" :model "opus"
                          :content [{:type "text" :text "On it"}
                                    {:type "tool_use" :id "e1" :name "Edit"
                                     :input {:file_path "/w/a.txt" :old_string "a\n" :new_string "b\n"}}]}}
               {:type "user" :message {:content [{:type "tool_result" :tool_use_id "e1" :is_error true}]}}
               {:type "assistant" :message {:id "m2" :content [{:type "text" :text "Done"}]}}]
        view (tasks/agent-view (apply str (map #(str (js/JSON.stringify (clj->js %)) "\n") lines)) "/w")]
    (is (= "Do the thing" (:prompt view)))
    (is (= "Done" (:said view)))
    (is (= "opus" (:model view)))
    (is (= ["m:0" "e1" "m2:0"] (map :id (:steps view))))
    (is (true? (:error (second (:steps view)))))
    (testing "the edit is counted under its file"
      (is (= [{:path "/w/a.txt" :rel "a.txt" :added 1 :removed 1
               :edits [{:name "Edit" :before "a\n" :after "b\n"}]}]
             (:files view))))))

(deftest running-ids-of-a-chat
  (is (= ["a"] (tasks/running-ids {"a" {:status "running"} "b" {:status "completed"} "c" {:status "stopped"}})))
  (is (= [] (tasks/running-ids nil))))

(deftest the-agent-clock-is-the-longest-running-top
  (let [m {"a" {:id "a" :status "running" :background true :type "task" :start 100}
           "b" {:id "b" :status "running" :background true :type "task" :start 40}
           "k" {:id "k" :status "running" :type "task" :start 10 :parent "a"}
           "d" {:id "d" :status "completed" :background true :type "task" :start 5}
           "f" {:id "f" :status "running" :type "local_bash" :start 1}}]
    (is (= 40 (tasks/oldest-running m)) "a child runs on its top's clock; done and foreground shells don't count")
    (is (nil? (tasks/oldest-running {"d" (m "d")})))))

;; The case seen on 2026-11-20: a background fork finished while the background shell it started
;; still ran (Claude Code keeps it running); the rail said "1 agent" and the list showed none running.
(def ^:private outlived
  {"fork" {:id "fork" :type "fork" :status "completed" :background true :start 10 :end 50}
   "fg" {:id "fg" :type "local_bash" :status "completed" :background false :start 12 :end 20 :parent "fork"}
   "early" {:id "early" :type "local_bash" :status "completed" :background true :start 15 :end 40 :parent "fork"}
   "sh" {:id "sh" :type "local_bash" :status "running" :background true :start 45 :parent "fork"}})

(deftest a-shell-that-outlives-its-agent-is-a-row-of-its-own
  (is (= #{"fork" "sh"} (set (map :id (tasks/tops outlived)))) "the shell that ended before its agent stays part of it")
  (is (= ["sh"] (map :id (tasks/running-tops outlived))))
  (is (= 45 (tasks/oldest-running outlived)) "the rail's clock is the shell's, a row the list shows running")
  (testing "while its agent runs, the shell is part of it"
    (let [m (assoc-in outlived ["fork" :status] "running")]
      (is (= ["fork"] (map :id (tasks/tops m))))
      (is (= 10 (tasks/oldest-running m)))))
  (testing "once it ends after its agent, it stays a row of its own"
    (let [m (update outlived "sh" assoc :status "completed" :end 90)]
      (is (= #{"fork" "sh"} (set (map :id (tasks/tops m)))))
      (is (empty? (tasks/running-tops m))))))

(deftest every-running-task-has-a-running-top
  (let [m (assoc outlived "kid" {:id "kid" :type "general-purpose" :status "running" :background true
                                 :start 30 :parent "fork"}
                 "kid-sh" {:id "kid-sh" :type "local_bash" :status "running" :background true
                           :start 31 :parent "kid"})
        by-id (into {} (map (juxt :id identity)) (vals m))]
    (is (= "kid" (:id (tasks/top-of (m "kid-sh") by-id))) "a running agent of a finished one keeps its own shells")
    (is (every? #(= "running" (:status (tasks/top-of % by-id))) (filter #(= "running" (:status %)) (vals m))))))

(deftest part-of-agent-decides-whose-notice-it-is
  (is (true? (tasks/part-of-agent? outlived "early")))
  (is (false? (tasks/part-of-agent? outlived "sh")) "its end is the chat's notice: its agent is gone")
  (is (false? (tasks/part-of-agent? outlived "fork")))
  (is (false? (tasks/part-of-agent? outlived "none"))))

(deftest a-held-sessions-live-set-ends-the-saved-tasks-that-finished
  (let [saved (tasks/loaded (vals outlived) true 60)
        after (tasks/with-live-set saved {} 70)]
    (is (= "running" (get-in saved ["sh" :status])) "a live session's saved shell may still run")
    (is (= {:status "stopped" :end 70} (select-keys (after "sh") [:status :end])))
    (is (empty? (tasks/running-tops after)))
    (is (= "running" (get-in (tasks/with-live-set saved {"sh" {:task_id "sh"}} 70) ["sh" :status])))))
