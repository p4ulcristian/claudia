(ns claudia.web.face.mind-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.face.mind :as mind]))

(defn- db [rows & {:as more}]
  (merge {:servers {"a" {:status :open :rows rows}}} more))

(deftest world-counts-chats
  (is (= {:running 1 :waiting 1 :offline? false}
         (mind/world-of (db {"1" {:running true} "2" {:waiting true} "3" {:running true :archived true}}))))
  (is (:offline? (mind/world-of {:servers {"a" {:status :closed}}})))
  (is (not (:offline? (mind/world-of {:servers {}})))))

(deftest moods
  (let [w {:running 0 :waiting 0 :offline? false}]
    (is (= :offline (mind/mood-of (assoc w :offline? true :waiting 2) 0 12)))
    (is (= :wait (mind/mood-of (assoc w :waiting 1 :running 5) 0 12)))
    (is (= :busy (mind/mood-of (assoc w :running 3) 0 12)))
    (is (= :work (mind/mood-of (assoc w :running 1) 0 12)))
    (is (= :sleep (mind/mood-of w (inc mind/sleep-after-ms) 12)))
    (is (= :sleepy (mind/mood-of w 0 23)))
    (is (= :idle (mind/mood-of w 0 14)))))

(deftest events-from-db
  (is (= [:done] (mind/db-events (db {"1" {:running true}}) (db {"1" {}}))))
  (is (= [:many-done] (mind/db-events (db {"1" {:running true} "2" {:running true}}) (db {"1" {} "2" {}}))))
  (is (= [:asked] (mind/db-events (db {"1" {}}) (db {"1" {:waiting true}}))))
  (is (= [:new-chat] (mind/db-events (db {"1" {}}) (db {"1" {} "2" {}}))))
  (is (= [:failed] (mind/db-events (db {}) (db {} :chats {["a" "1"] {:error "boom"}}))))
  (is (= [:list-open] (mind/db-events (db {}) (db {} :sidebar? true))))
  (is (= [:switch] (mind/db-events (db {} :open ["a" "1"]) (db {} :open ["a" "2"]))))
  (testing "sending beats typing (the draft empties as it goes)"
    (is (= [:sent] (mind/db-events (db {} :drafts {:new "hi"}) (db {} :outbox {["a" "1"] ["hi"]})))))
  (is (= [:typing] (mind/db-events (db {} :drafts {:new "h"}) (db {} :drafts {:new "hi"})))))

(deftest clips-start-by-strength
  (let [s (mind/initial 0)]
    (is (= :done (get-in (mind/start-clip s :done 10) [:clip :id])))
    (testing "a weaker clip does not cut a stronger one"
      (is (= :failed (get-in (-> s (mind/start-clip :failed 10) (mind/start-clip :typing 20)) [:clip :id]))))
    (testing "after it ends, anything goes"
      (is (= :typing (get-in (-> s (mind/start-clip :failed 10) (mind/start-clip :typing 5000)) [:clip :id]))))))

(deftest reactions
  (let [s (mind/initial 0)]
    (is (= :giggle (get-in (mind/react s :click 100) [:clip :id])))
    (is (= :dizzy (get-in (reduce #(mind/react %1 :click %2) s [100 200 300 400]) [:clip :id])))
    (is (:hover? (mind/react s :hover-in 5)))
    (testing "hello only after a while away"
      (is (nil? (:clip (-> s (mind/react :hidden 0) (mind/react :visible 1000)))))
      (is (= :hello (get-in (-> s (mind/react :hidden 0) (mind/react :visible 70000)) [:clip :id]))))
    (testing "a poke wakes her"
      (is (= :wake (get-in (mind/react (assoc s :mood :sleep) :hover-in 5) [:clip :id]))))))

(deftest a-step-of-life
  (let [s (mind/tick (mind/initial 0) 5000 33 [0.5 0.5 0.5 0.5] 12)]
    (is (= :idle (:mood s)))
    (is (some? (:clip s)) "after 4 s alone she does something of her own")
    (is (map? (:pose s)))
    (is (vector? (:fx (:pose s))))
    (testing "working: the thinking bubble"
      (is (= [[:dots false]] (:fx (:pose (mind/tick (assoc (mind/initial 0) :world {:running 1 :waiting 0})
                                                     100 33 [0.5 0.5 0.5 0.5] 12)))))))
  (testing "the mouse pulls her look"
    (let [s (-> (mind/initial 0) (mind/point [1 1] 10) (mind/tick 100 100 [0.9 0.9 0.9 0.9] 12))]
      (is (pos? (first (:look s)))))))
