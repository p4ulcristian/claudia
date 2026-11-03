(ns claudia.web.pictures-test
  (:require [cljs.test :refer [deftest is testing]]
            [claudia.web.pictures :as pictures]))

(def ^:private never (constantly false))
(def ^:private always (constantly true))

(deftest key-event-for-the-shortcuts
  (is (= [:right/view nil] (pictures/key-event {:alt true :code "KeyT"} never never)))
  (is (= [:right/fold] (pictures/key-event {:alt true :code "KeyP"} never never)))
  (is (nil? (pictures/key-event {:alt true :ctrl true :code "KeyT"} never never)))
  (testing "arrows step only on the picture, not while typing"
    (is (= [:picture/step -1] (pictures/key-event {:k "ArrowLeft"} never always)))
    (is (= [:picture/step 1] (pictures/key-event {:k "ArrowRight"} never always)))
    (is (nil? (pictures/key-event {:k "ArrowRight"} always always)))
    (is (nil? (pictures/key-event {:k "ArrowRight"} never never)))
    (is (nil? (pictures/key-event {:k "ArrowRight" :shift true} never always)))))

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
