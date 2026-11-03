(ns claudia.web.views.pure.timeline-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.views.pure.timeline :as tl]))

(deftest durations-and-categories
  (is (= "0.4s" (tl/dur 400)))
  (is (= "12s" (tl/dur 12000)))
  (is (= "3m 5s" (tl/dur 185000)))
  (is (= "2h 10m" (tl/dur (* 130 60000))))
  (is (= "1d 3h" (tl/dur (* 27 3600000))))
  (is (= "read" (tl/category "Grep")))
  (is (= "edit" (tl/category "Write")))
  (is (= "other" (tl/category "Draw"))))

(def lanes
  [{:id "main" :kind "main" :events [{:id "1" :k "user" :at 1} {:id "2" :k "tool" :start 5}]}
   {:id "a" :kind "agent" :parent "main" :events [{:id "3" :k "tool" :start 3}]}
   {:id "b" :kind "agent" :parent "a" :events [{:id "4" :k "tool" :start 4}]}
   {:id "c" :kind "agent" :parent "main" :running true :events [{:id "5" :k "tool" :start 6}]}])

(deftest kept-lanes-follow-the-agents-started
  (is (nil? (tl/kept-lanes lanes nil)))
  (is (= #{"main"} (tl/kept-lanes lanes "main")))
  (is (= #{"a" "b"} (tl/kept-lanes lanes "a"))))

(deftest rows-in-time-order
  (let [rows (tl/all-rows lanes nil)]
    (is (= ["1" "3" "4" "2" "5"] (map #(get-in % [:e :id]) rows)))
    (is (= ["3" "4"] (map #(get-in % [:e :id]) (tl/all-rows lanes #{"a" "b"}))))
    (is (= ["2" "5"] (map #(get-in % [:e :id]) (tl/last-rows rows 2))))))

(deftest rails-are-reused
  (let [rails (tl/rails-of (tl/all-rows lanes nil))]
    (testing "the chat on rail 0; a, then b and c each take the rail the one before freed"
      (is (= {"main" 0 "a" 1 "b" 1 "c" 1} (:col rails))))
    (is (= 2 (:count rails)))
    (testing "a running lane runs to the bottom"
      (is (= 5 ((:last rails) "c"))))))

(deftest dividers
  (let [day-of #(quot % 100)]
    (is (nil? (tl/divider nil {:at 5} 10 day-of)))
    (is (= :day (tl/divider {:at 50} {:at 150} 1000 day-of)))
    (is (= :quiet (tl/divider {:at 10} {:at 50} 30 day-of)))
    (is (nil? (tl/divider {:at 10} {:at 20} 30 day-of)))))

(deftest hunk-rows-number-the-lines
  (is (= [{:kind "ctx" :a 3 :b 7 :text "a"}
          {:kind "del" :a 4 :b nil :text "b"}
          {:kind "add" :a nil :b 8 :text "c"}
          {:kind "meta" :a nil :b nil :text " No newline"}
          {:kind "ctx" :a 5 :b 9 :text "d"}]
         (tl/hunk-rows {:oldStart 3 :newStart 7 :lines [" a" "-b" "+c" "\\ No newline" " d"]}))))

(deftest words-of-the-head-and-rows
  (is (= "no agents yet" (tl/agents-summary [])))
  (is (= "2 agents · 1 running" (tl/agents-summary [{:running true} {}])))
  (is (= "1 agent" (tl/agents-summary [{}])))
  (is (= {"a" "sky" "b" "violet"} (tl/agent-colors [{:id "a"} {:id "b"}])))
  (is (= "busy" (tl/step-class {:running true} {:k "tool"})))
  (is (= "lost" (tl/step-class {} {:k "tool"})))
  (is (= "err" (tl/step-class {} {:k "tool" :error "x" :end 1})))
  (is (= "user" (tl/step-class {} {:k "user"})))
  (is (= "You" (tl/who {:kind "main"} {:k "user"})))
  (is (= "Task" (tl/who {:kind "agent"} {:k "user"})))
  (is (= "Claude" (tl/who {} {:k "text"}))))
