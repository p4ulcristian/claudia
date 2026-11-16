(ns claudia.web.pictures-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.events :as events]
            [claudia.web.pictures :as pictures]))

(def ^:private never (constantly false))
(def ^:private always (constantly true))

(deftest key-event-for-the-shortcuts
  (is (nil? (pictures/key-event {:alt true :code "KeyT"} never)))
  (is (= [:right/fold] (pictures/key-event {:alt true :code "KeyP"} never)))
  (is (nil? (pictures/key-event {:alt true :ctrl true :code "KeyP"} never)))
  (testing "arrows step through the pictures, not while typing"
    (is (= [:picture/step -1] (pictures/key-event {:k "ArrowLeft"} never)))
    (is (= [:picture/step 1] (pictures/key-event {:k "ArrowRight"} never)))
    (is (nil? (pictures/key-event {:k "ArrowRight"} always)))
    (is (nil? (pictures/key-event {:k "ArrowRight" :shift true} never)))))

(deftest drew-nothing-when-asked-after-the-last-picture
  (let [pics [{:turn 1 :at 10}]]
    (is (true? (pictures/drew-nothing? pics {:messages [{:role "user" :at 20} {:role "assistant" :at 30}]})))
    (is (false? (pictures/drew-nothing? pics {:messages [{:role "user" :at 5}]})))
    (is (false? (pictures/drew-nothing? pics {:running true :messages [{:role "user" :at 20}]})))
    (is (false? (pictures/drew-nothing? [] {:messages [{:role "user" :at 20}]})))))

(deftest shown-index-is-the-viewed-or-the-newest
  (is (= 1 (pictures/shown-index [{:turn 1} {:turn 2}] nil)))
  (is (= 0 (pictures/shown-index [{:turn 1} {:turn 2}] 1)))
  (is (= -1 (pictures/shown-index [] nil))))

(deftest a-new-picture-brings-the-live-one-back
  (let [db {:pictures {["s" "c"] [{:turn "t1"} {:turn "t2"}]} :viewing {["s" "c"] "t1"}}
        frame (fn [turns] (:db (events/on-frame db "s" {:type "pictures" :chat "c" :pictures (mapv #(hash-map :turn %) turns)})))]
    (is (nil? (get-in (frame ["t1" "t2" "t3"]) [:viewing ["s" "c"]])) "t3 came: live again")
    (is (= "t1" (get-in (frame ["t1" "t2"]) [:viewing ["s" "c"]])) "nothing new: still looking at t1")))
