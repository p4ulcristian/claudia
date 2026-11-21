(ns claudia.web.views.pure.edge-test
  (:require [cljs.test :refer [deftest is]]
            [claudia.web.views.pure.edge :as edge]))

(def ^:private idle {:running? false :new? true :folded? false :index 1 :n 3})

(deftest four-slots-over-the-mic-and-four-under-it
  (is (= 4 (count edge/over) (count edge/under)))
  (is (= [:search :system :prompts :settings] edge/over))
  (is (= [:stop :new :prev :next] edge/under)))

(deftest every-slot-has-a-state-always
  (is (= (set (concat edge/over edge/under)) (set (keys (edge/slot-states {})))))
  (is (every? #{:shown :passive :red} (vals (edge/slot-states {})))))

(deftest stop-is-passive-while-nothing-runs-and-red-while-a-turn-runs
  (is (= :passive (:stop (edge/slot-states idle))))
  (is (= :red (:stop (edge/slot-states (assoc idle :running? true))))))

(deftest new-chat-is-passive-where-a-new-chat-cannot-be-written
  (is (= :passive (:new (edge/slot-states (assoc idle :new? false))))))

(deftest the-steps-act-only-towards-a-picture-that-is-there
  (is (= [:shown :shown] ((juxt :prev :next) (edge/slot-states idle))))
  (is (= [:passive :shown] ((juxt :prev :next) (edge/slot-states (assoc idle :index 0)))))
  (is (= [:shown :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :index 2)))))
  (is (= [:passive :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :index 0 :n 1)))))
  (is (= [:passive :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :index nil :n 0))))))

(deftest a-folded-picture-makes-both-steps-passive
  (is (= [:passive :passive] ((juxt :prev :next) (edge/slot-states (assoc idle :folded? true))))))

(deftest search-system-prompts-and-settings-always-act
  (is (= [:shown :shown :shown :shown] ((juxt :search :system :prompts :settings) (edge/slot-states {})))))
