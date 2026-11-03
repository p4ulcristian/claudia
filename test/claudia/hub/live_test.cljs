(ns claudia.hub.live-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.hub.live :as live]))

(deftest cut-title-fits-one-line
  (is (= "Chat" (live/cut-title "  \n ")))
  (is (= "a b" (live/cut-title "a\n b")))
  (is (= 40 (count (live/cut-title (apply str (repeat 50 "x"))))))
  (is (= "…" (last (live/cut-title (apply str (repeat 50 "x")))))))

(deftest noted-marks-urgent-and-quiet
  (let [waiting {:waiting [{:server "s" :chat "c"}] :running []}
        nothing {:waiting [] :running []}]
    (is (= {:last 0 :waiting #{"s/c"} :urgent true :quiet-since nil}
           (live/noted {:last 0 :waiting #{}} waiting 5)))
    (testing "a chat already waiting is not urgent again"
      (is (= {:waiting #{"s/c"} :quiet-since nil} (live/noted {:waiting #{"s/c"}} waiting 5))))
    (testing "quiet since the first time nothing runs"
      (is (= {:waiting #{} :quiet-since 5} (live/noted {:waiting #{}} nothing 5)))
      (is (= {:waiting #{} :quiet-since 3} (live/noted {:waiting #{} :quiet-since 3} nothing 5))))))

(deftest outcome-adds-up-nested-results
  (is (= {:sent false :again false} (live/outcome [])))
  (is (= {:sent false :again false} (live/outcome [nil [nil]])))
  (is (= {:sent true :again false} (live/outcome [nil [{:sent true :again false}]])))
  (is (= {:sent true :again true} (live/outcome [{:sent true :again false} [nil {:sent true :again true}]]))))

(deftest card-step-picks-what-a-phone-needs
  (is (= :update (live/card-step {} [{:activity "a"}] true false)))
  (is (= :update (live/card-step {} [{:activity "a"}] false true)))
  (is (= :start (live/card-step {:live-start "t"} [] true false)))
  (is (nil? (live/card-step {:live-start "t" :live-started-at 1} [] true false)))
  (is (nil? (live/card-step {} [] true false)))
  (is (= :end (live/card-step {} [{:activity "a"}] false false)))
  (is (= :end (live/card-step {} [] false false)))
  (is (nil? (live/card-step {} [] false true))))

(deftest next-flush-after-a-retry-or-while-quiet
  (is (= 30100 (live/next-flush {:again true} true 0 0 100)))
  (is (= 30000 (live/next-flush {:again false} true 0 0 100)))
  (is (= 45000 (live/next-flush {} true 0 40000 100)))
  (is (nil? (live/next-flush {} false 0 0 100))))

(deftest state-sig-ignores-the-time
  (is (= (live/state-sig {:running [] :updatedAt 1}) (live/state-sig {:running [] :updatedAt 2}))))

(deftest content-of-cuts-lists-to-fit
  (let [chat (fn [i] {:title (str "chat " i) :server "s" :chat (str "c" i) :since i :kind "ask"})
        s {:running (map chat (range 7)) :waiting (map chat (range 2)) :done 3}
        c (live/content-of s 9 #(live/update-push 9 %))]
    (is (= 5 (count (:running c))))
    (is (every? nil? (map :kind (:running c))))
    (is (= ["ask" "ask"] (map :kind (:waiting c))))
    (is (= {:done 3 :updatedAt 9} (select-keys c [:done :updatedAt]))))
  (testing "a push over 4 KB gives up items, the longer list first"
    (let [big (apply str (repeat 39 "x"))
          chat (fn [i] {:title big :server (apply str (repeat 500 "s")) :chat (str "c" i) :since i})
          c (live/content-of {:running (map chat (range 5)) :waiting (map chat (range 5)) :done 0}
                             1 #(live/update-push 1 %))]
      (is (< (+ (count (:running c)) (count (:waiting c))) 10))
      (is (<= (count (:waiting c)) (inc (count (:running c))))))))
