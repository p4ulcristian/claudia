(ns claudia.hub.push-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.push :as push]))

(def now 1000000000)

(deftest notices-of-tells-asks-and-fresh-turns
  (let [rows [{:id "c1" :title "Fix\nthe build" :asks [{:id "q1" :kind "question" :text "Which one?"}
                                                       {:id "s1" :kind "secret"}]}
              {:id "c2" :title "Docs" :lastTurn {:id "t1" :ago 1000 :reply "Done."}}
              {:id "c3" :lastTurn {:id "t2" :ago 1000 :failed true}}
              {:id "c4" :archived true :asks [{:id "q2" :kind "question"}]}
              {:id "bad id" :asks [{:id "q3" :kind "question"}]}
              {:id "c5" :lastTurn {:id "t3" :ago 1000 :stopped true}}
              {:id "c6" :agents 1 :lastTurn {:id "t4" :ago 1000}}
              {:id "c7" :unseen false :lastTurn {:id "t5" :ago 1000}}
              {:id "c8" :lastTurn {:id "t6" :ago (* 11 60000)}}]]
    (is (= [{:server "s" :chat "c1" :title "Fix the build" :kind "ask" :event "ask:q1" :body "Which one?"}
            {:server "s" :chat "c1" :title "Fix the build" :kind "permission" :event "ask:s1"
             :body "Needs your permission: a secret"}
            {:server "s" :chat "c2" :title "Docs" :kind "finished" :event "turn:t1" :body "Done."}
            {:server "s" :chat "c3" :title "Chat" :kind "finished" :event "turn:t2" :body "Ended with an error"}]
           (push/notices-of "s" rows 0 now)))
    (testing "a turn that ended before the first phone is not news"
      (is (= [] (push/notices-of "s" [{:id "c2" :lastTurn {:id "t1" :ago 1000}}] now now))))))

(deftest row-state-classifies-a-chat
  (is (= :waiting (push/row-state {:asks [{:id "q"}]})))
  (is (= :waiting (push/row-state {:waiting true :running true})))
  (is (= :running (push/row-state {:running true})))
  (is (= :running (push/row-state {:agents 2})))
  (is (= :done (push/row-state {:unseen true})))
  (is (nil? (push/row-state {:archived true :running true})))
  (is (nil? (push/row-state {}))))

(deftest wait-kind-is-ask-when-any-question
  (is (= "ask" (push/wait-kind [{:kind "secret"} {:kind "question"}])))
  (is (= "permission" (push/wait-kind [{:kind "secret"}])))
  (is (= "permission" (push/wait-kind []))))

(deftest started-at-is-seconds
  (is (= 999995 (push/started-at 5000 now)))
  (is (= 1000000 (push/started-at nil now)))
  (is (= 1000000 (push/started-at -50 now))))

(deftest steady-keeps-a-start-within-ten-seconds
  (is (= {"a" 100 "b" 200 "c" 300}
         (push/steady {"a" 100 "b" 150} {"a" 108 "b" 200 "c" 300}))))

(deftest next-times-drops-the-persons-old-keys-only
  (is (= {"a@x\ns/c1\nrun" 100 "b@y\ns/c9\nrun" 7}
         (push/next-times {"a@x\ns/old\nrun" 5 "b@y\ns/c9\nrun" 7} "a@x" {"a@x\ns/c1\nrun" 100}))))

(defn- summarize [email lists prev]
  (let [found (push/chat-clocks email lists now)
        fresh (into {} (map (juxt :key :at)) (:clocks found))
        times (push/next-times prev email fresh)]
    [(push/summary found times) times]))

(deftest summary-of-a-waiting-and-a-running-chat
  ;; The hub crashed on a chat waiting on asks with :ago values (a local `keep` shadowed
  ;; clojure.core/keep): this is that list.
  (let [lists [["s1" [{:id "c1" :title "Fix" :asks [{:id "q1" :kind "question" :ago 5000}
                                                    {:id "q2" :kind "secret" :ago 2000}]}
                      {:id "c2" :title "" :running true :runningFor 60000}
                      {:id "c3" :unseen true}
                      {:id "c4" :archived true :running true}]]]
        prev {"a@x\ns1/c2\nrun" 999945 "a@x\ns1/old\nrun" 5 "b@y\ns1/c9\nrun" 7}
        [s times] (summarize "a@x" lists prev)]
    (is (= {:waiting [{:title "Fix" :server "s1" :chat "c1" :kind "ask" :since 999995}]
            :running [{:title "Chat" :server "s1" :chat "c2" :kind nil :since 999945}]
            :done 1}
           s))
    (is (= {"a@x\ns1/c1\nwait:q1,q2" 999995 "a@x\ns1/c2\nrun" 999945 "b@y\ns1/c9\nrun" 7} times))))

(deftest summary-lists-are-oldest-first
  (let [lists [["s" [{:id "new" :running true :runningFor 1000}
                     {:id "old" :running true :runningFor 50000}
                     {:id "w" :waiting true}]]]
        [s] (summarize "a@x" lists {})]
    (is (= ["old" "new"] (map :chat (:running s))))
    (is (= [{:title "Chat" :server "s" :chat "w" :kind "permission" :since 1000000}] (:waiting s)))))

(deftest with-held-keeps-each-event-once-per-chat
  (let [n1 {:server "s" :chat "c" :event "ask:1"}
        n2 {:server "s" :chat "c" :event "turn:2"}
        n3 {:server "s" :chat "d" :event "ask:1"}]
    (is (= {"s/c" [n1 n2] "s/d" [n3]} (push/with-held nil [n1 n2 n1 n3])))
    (is (= {"s/c" [n1 n2]} (push/with-held {"s/c" [n1]} [n2 n1])))))

(deftest matters-while-the-chat-still-shows-it
  (let [turn {:kind "finished" :event "turn:t1"}
        ask {:kind "ask" :event "ask:q1"}]
    (is (true? (push/matters? {:lastTurn {:id "t1"}} turn)))
    (is (false? (push/matters? {:lastTurn {:id "t2"}} turn)))
    (is (false? (push/matters? {:lastTurn {:id "t1"} :running true} turn)))
    (is (false? (push/matters? {:lastTurn {:id "t1"} :unseen false} turn)))
    (is (true? (push/matters? {:asks [{:id "q1"}]} ask)))
    (is (false? (push/matters? {:asks []} ask)))
    (is (false? (push/matters? nil ask)))))

(deftest backoff-grows-and-stops-at-a-minute
  (is (= 800 (push/backoff 0 0)))
  (is (= 1000 (push/backoff 0 0.5)))
  (is (= 48000 (push/backoff 99 0))))

(deftest chats-of-reads-only-chats-frames
  (is (= [{:id "c"}] (push/chats-of "{\"type\":\"chats\",\"chats\":[{\"id\":\"c\"}]}")))
  (is (= [] (push/chats-of "{\"type\":\"chats\",\"chats\":[]}")))
  (is (nil? (push/chats-of "{\"type\":\"chat\",\"chats\":[]}")))
  (is (nil? (push/chats-of "{\"type\":\"chats\",\"chats\":{}}")))
  (is (nil? (push/chats-of "not json"))))

(deftest notices-of-rings-fresh-alarms-once-each
  (let [rows [{:id "c1" :title "Deploy" :alarms [{:id "a1" :text "The deploy\nis done" :ago 1000}
                                                 {:id "a2" :text "Old" :ago (* 11 60000)}
                                                 {:id "a3" :text "" :ago 10}]}
              {:id "c2" :archived true :alarms [{:id "a4" :text "Archived" :ago 10}]}]]
    (is (= [{:server "s" :chat "c1" :title "Deploy" :kind "alarm" :event "alarm:a1" :body "The deploy is done"}
            {:server "s" :chat "c1" :title "Deploy" :kind "alarm" :event "alarm:a3" :body "Alarm"}]
           (push/notices-of "s" rows 0 now)))
    (testing "an alarm rung before the first phone is not news"
      (is (= [] (push/notices-of "s" [{:id "c1" :alarms [{:id "a1" :text "x" :ago 1000}]}] now now))))))

(deftest payload-of-an-alarm-is-time-sensitive-and-says-its-kind
  (let [p (push/payload-of {:server "s" :chat "c1" :kind "alarm" :title "Deploy" :body "Done"}
                           (.toString (js/Buffer.alloc 32 1) "base64") 2)]
    (is (= "alarm" (:kind p)))
    (is (= "time-sensitive" (get-in p [:aps :interruption-level])))
    (is (= 1 (get-in p [:aps :mutable-content])))))

(deftest split-news-holds-all-but-alarms-while-present
  (let [ask {:kind "ask" :event "ask:q1"}
        alarm {:kind "alarm" :event "alarm:a1"}]
    (is (= {:send [alarm] :hold [ask]} (push/split-news [ask alarm] true)))
    (is (= {:send [ask alarm] :hold []} (push/split-news [ask alarm] false)))))
