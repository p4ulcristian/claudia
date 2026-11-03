(ns claudia.slave.store-meta-fork-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.slave.fork :as fork]
            [claudia.slave.meta :as meta]
            [claudia.slave.store :as store]
            [claudia.slave.timeline :as timeline]))

;; store

(deftest update-statement-sets-known-columns
  (is (= ["UPDATE chats SET title = ?, stopped = ? WHERE id = ?" ["T" 1 "c"]]
         (store/update-statement "c" {:title "T" :stopped true :nope 1})))
  (is (nil? (store/update-statement "c" {:id "x" :nope 1})) "the id is never set"))

;; meta

(deftest limits-from-a-rate-limit-event
  (is (= 50 (meta/percent 0.5)))
  (is (= 72 (meta/percent 72)))
  (is (= [{:key "five_hour" :label "5-hour" :percent 10 :resetsAt 5000}]
         (meta/event-limits {:unifiedWindows {:five_hour {:utilization 0.1 :resetsAt 5}}})))
  (is (= [{:key "seven_day" :label "Week" :percent 30 :resetsAt nil}]
         (meta/event-limits {:rateLimitType "seven_day" :utilization 0.3})))
  (is (nil? (meta/event-limits {:rateLimitType "other" :utilization 0.3}))))

(deftest merge-limits-keeps-the-labels-order
  (is (= [{:key "five_hour" :percent 2} {:key "seven_day" :percent 1}]
         (meta/merge-limits [{:key "seven_day" :percent 1} {:key "five_hour" :percent 9}]
                            [{:key "five_hour" :percent 2}]))))

(deftest limits-from-the-usage-call
  (is (= [{:key "five_hour" :label "5-hour" :percent 40 :resetsAt 0}
          {:key "model:Opus" :label "Week, Opus" :percent 5 :resetsAt nil}]
         (meta/limits-from {:five_hour {:utilization 40 :resets_at "1970-01-01T00:00:00Z"}
                            :model_scoped [{:display_name "Opus" :utilization 5} {:display_name "X" :utilization 0}]}))))

(deftest models-and-commands-as-data
  (is (= [{:value "m" :name "M" :efforts ["low"]}]
         (meta/models-of [#js {:value "m" :displayName "M" :supportedEffortLevels #js ["low"]}])))
  (is (= ["a" "b"] (map :name (meta/commands-of [#js {:name "b"} #js {:name "a"}])))))

;; fork

(def ^:private chain
  [{:type "user" :uuid "u1" :message {:content "hi"}}
   {:type "assistant" :uuid "a1" :message {:stop_reason "end_turn" :content [{:type "text"}]}}
   {:type "user" :uuid "u2" :message {:content "more"}}
   {:type "assistant" :uuid "a2" :message {:stop_reason "tool_use" :content [{:type "tool_use"}]}}
   {:type "user" :uuid "r2" :message {:content [{:type "tool_result"}]}}
   {:type "assistant" :uuid "a3" :message {:stop_reason "end_turn" :content [{:type "text"}]}}])

(deftest turn-starts-are-typed-messages-after-a-finished-turn
  (is (= [0 2] (fork/turn-starts chain))))

(deftest cut-at-ends-after-a-turn
  (is (= 2 (fork/cut-at chain "u1" false)))
  (is (nil? (fork/cut-at chain "u2" false)) "the last turn: all of it")
  (is (= 2 (fork/cut-at chain nil true)) "running: up to the last finished turn")
  (is (thrown? js/Error (fork/cut-at chain "u2" true)))
  (is (thrown? js/Error (fork/cut-at chain "nope" false)))
  (is (thrown? js/Error (fork/cut-at [] nil false))))

(deftest turn-map-follows-forked-from
  (is (= {"u1" "n1" "q" "nq" "sent" "nsent"}
         (fork/turn-map {"q" #js {:type "attachment" :uuid "q"
                                  :attachment #js {:type "queued_command" :prompt "x" :source_uuid "sent"}}}
                        [#js {:uuid "n1" :forkedFrom #js {:messageUuid "u1"}}
                         #js {:uuid "nq" :forkedFrom #js {:messageUuid "q"} :type "attachment"
                              :attachment #js {:type "queued_command" :prompt "x" :source_uuid "nsent"}}
                         #js {:uuid "n3"}]))
      "a message sent mid-turn maps by its sent uuid too")
  (is (= "nq" (get (fork/turn-map {} [#js {:uuid "nq" :forkedFrom #js {:messageUuid "q"}}]) "q"))))

;; timeline

(deftest cut-hunks-keeps-at-most-the-patch-lines
  (let [[hunks cut] (timeline/cut-hunks [#js {:oldStart 1 :oldLines 1 :newStart 1 :newLines 2 :lines #js ["-a" "+b" "+c"]}])]
    (is (= [{:oldStart 1 :oldLines 1 :newStart 1 :newLines 2 :lines ["-a" "+b" "+c"]}] hunks))
    (is (false? cut)))
  (let [big (into-array (repeat 4999 "+x"))
        [hunks cut] (timeline/cut-hunks [#js {:lines big} #js {:lines #js ["+y" "+z"]} #js {:lines #js ["+w"]}])]
    (is (= 2 (count hunks)) "a hunk past the limit is left out")
    (is (= ["+y"] (:lines (second hunks))))
    (is (true? cut))))
